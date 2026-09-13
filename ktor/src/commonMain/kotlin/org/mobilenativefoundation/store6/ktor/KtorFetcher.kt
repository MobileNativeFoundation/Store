package org.mobilenativefoundation.store6.ktor

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreBuilder
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult

/**
 * Builds a [Fetcher] that revalidates over HTTP on [client].
 *
 * @param client caller-owned HTTP client; the kit never closes it. Must not have Ktor's HttpCache
 *   plugin installed unless [allowHttpCache] is true (see the technical design §13.2). Must not
 *   contribute `If-None-Match` or `If-Modified-Since` from `defaultRequest` or from any other
 *   plugin: the kit's header removal is scoped to the request builder and cannot reach a header
 *   the request pipeline adds afterwards, so such a header reaches the server without the kit
 *   knowing. The kit refuses the resulting 304 rather than trusting it — as a protocol anomaly
 *   when it sent no validator of its own, and as a conditional request carrying validators it did
 *   not set when it did, which it detects by comparing the headers the request actually carried
 *   against the single one it wrote.
 * @param decode maps an adopted 2xx response to a value; invoked inside the response scope only for
 *   outcomes the kit adopts as Success. The default table never calls it for 204, 205, or 206,
 *   because none of those carries a representation. A caller who wants different handling for
 *   those statuses opts in through [errorMapper]; note that no [KtorOutcome] adopts a body, so a
 *   mapper can map them to Delete, Fail, or NotModified but never to a value.
 * @param notFoundPolicy how 404 and 410 are mapped (default: typed error, non-destructive)
 * @param lastModifiedFallback whether to record and send Last-Modified when no ETag is available
 * @param errorMapper optional override of status-to-result mapping; returns Defer to keep defaults
 * @param allowHttpCache set true only when you accept that HttpCache can intercept the 304 path
 * @param configureRequest applies the per-key request shape (method, URL, headers, body). The kit
 *   removes `If-None-Match` and `If-Modified-Since` after this lambda runs and then sets at most
 *   one of them from its recorded validator, so a conditional header set here is always discarded.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> ktorFetcher(
    client: HttpClient,
    notFoundPolicy: KtorNotFoundPolicy = KtorNotFoundPolicy.Error,
    lastModifiedFallback: Boolean = true,
    errorMapper: KtorErrorMapper = KtorErrorMapper.Default,
    allowHttpCache: Boolean = false,
    decode: suspend (HttpResponse) -> V,
    configureRequest: HttpRequestBuilder.(K) -> Unit,
): Fetcher<K, V> {
    requireNoHttpCache(client, allowHttpCache)
    return createKtorFetcher(
        client = client,
        notFoundPolicy = notFoundPolicy,
        lastModifiedFallback = lastModifiedFallback,
        errorMapper = errorMapper,
        decode = decode,
        configureRequest = configureRequest,
    )
}

/** Installs [ktorFetcher] as this store's fetch source. Last fetcher registration wins. */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> StoreBuilder<K, V>.ktorFetcher(
    client: HttpClient,
    notFoundPolicy: KtorNotFoundPolicy = KtorNotFoundPolicy.Error,
    lastModifiedFallback: Boolean = true,
    errorMapper: KtorErrorMapper = KtorErrorMapper.Default,
    allowHttpCache: Boolean = false,
    decode: suspend (HttpResponse) -> V,
    configureRequest: HttpRequestBuilder.(K) -> Unit,
) {
    val theFetcher =
        org.mobilenativefoundation.store6.ktor.ktorFetcher(
            client = client,
            notFoundPolicy = notFoundPolicy,
            lastModifiedFallback = lastModifiedFallback,
            errorMapper = errorMapper,
            allowHttpCache = allowHttpCache,
            decode = decode,
            configureRequest = configureRequest,
        )
    fetcher(theFetcher)
}

@ExperimentalStoreApi
internal fun <K : StoreKey, V : Any> createKtorFetcher(
    client: HttpClient,
    notFoundPolicy: KtorNotFoundPolicy,
    lastModifiedFallback: Boolean,
    errorMapper: KtorErrorMapper,
    decode: suspend (HttpResponse) -> V,
    configureRequest: HttpRequestBuilder.(K) -> Unit,
): Fetcher<K, V> =
    KtorFetcher(
        client = client,
        notFoundPolicy = notFoundPolicy,
        lastModifiedFallback = lastModifiedFallback,
        errorMapper = errorMapper,
        decode = decode,
        configureRequest = configureRequest,
    )

internal fun requireNoHttpCache(
    client: HttpClient,
    allowHttpCache: Boolean,
) {
    if (!allowHttpCache && client.pluginOrNull(HttpCache) != null) {
        throw IllegalArgumentException(
            "Ktor's HttpCache plugin conflicts with conditional revalidation because it can " +
                "intercept 304 responses; pass allowHttpCache = true to accept this interaction.",
        )
    }
}

@OptIn(DelicateStoreApi::class)
@ExperimentalStoreApi
private class KtorFetcher<K : StoreKey, V : Any>(
    private val client: HttpClient,
    private val notFoundPolicy: KtorNotFoundPolicy,
    private val lastModifiedFallback: Boolean,
    private val errorMapper: KtorErrorMapper,
    private val decode: suspend (HttpResponse) -> V,
    private val configureRequest: HttpRequestBuilder.(K) -> Unit,
) : Fetcher<K, V> {
    override suspend fun fetch(
        key: K,
        etag: String?,
    ): FetcherResult<V> =
        try {
            // decodeValidatorToken yields at most one of the two headers, so the kit writes at
            // most one conditional header per request. The guard below depends on that.
            var sentValidator: SentValidator? = null
            client
                .prepareRequest {
                    configureRequest(key)
                    expectSuccess = false
                    headers.remove(HttpHeaders.IfNoneMatch)
                    headers.remove(HttpHeaders.IfModifiedSince)

                    if (etag != null && (method == HttpMethod.Get || method == HttpMethod.Head)) {
                        val validatorHeaders =
                            decodeValidatorToken(
                                token = etag,
                                lastModifiedFallback = lastModifiedFallback,
                            )
                        validatorHeaders?.ifNoneMatch?.let { value ->
                            headers[HttpHeaders.IfNoneMatch] = value
                            sentValidator = SentValidator(HttpHeaders.IfNoneMatch, value)
                        }
                        validatorHeaders?.ifModifiedSince?.let { value ->
                            headers[HttpHeaders.IfModifiedSince] = value
                            sentValidator = SentValidator(HttpHeaders.IfModifiedSince, value)
                        }
                    }
                }.execute { response ->
                    mapResponse(response, sentValidator)
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // An engine may surface a cancelled call as its own exception type rather than as a
            // CancellationException (Darwin's NSURLErrorCancelled, OkHttp's IOException("Canceled"),
            // the JS AbortError). Recording that as a fetch failure would give the key an error
            // result and a failure record for a coroutine that is no longer alive.
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancellation: CancellationException) {
                // ensureActive() throws its own CancellationException carrying the job's
                // cancellation cause, unrelated to `failure`. Without attaching it, the original
                // non-cancellation failure's information is silently dropped.
                cancellation.addSuppressed(failure)
                throw cancellation
            }
            FetcherResult.Error(failure)
        }

    private suspend fun mapResponse(
        response: HttpResponse,
        sentValidator: SentValidator?,
    ): FetcherResult<V> {
        val exchange =
            KtorExchange(
                status = response.status,
                method = response.request.method,
                url = response.request.url.toString(),
                conditional = sentValidator != null,
                response = response,
            )
        if (sentValidator != null && exchange.status == HttpStatusCode.NotModified) {
            // The exchange is uninterpretable, not merely unwelcome: the kit cannot know which
            // validator the server compared, so no mapper can recover the meaning of this 304
            // either. Refused before the mapper runs, unlike the KtorOutcome.NotModified rule
            // below, which needs the outcome as well as the exchange to decide.
            foreignValidatorRefusal(exchange, sentValidator)?.let { return it }
        }
        return when (val outcome = errorMapper.map(exchange)) {
            KtorOutcome.Defer -> mapDefault(exchange)
            is KtorOutcome.Fail -> FetcherResult.Error(outcome.exception)
            KtorOutcome.Delete -> FetcherResult.Deleted
            is KtorOutcome.NotModified ->
                if (exchange.conditional) {
                    FetcherResult.NotModified(outcome.validatorToken)
                } else {
                    statusError(
                        exchange,
                        "KtorOutcome.NotModified requires a conditional request: this exchange " +
                            "sent no validator, so nothing was compared and freshness cannot be " +
                            "refreshed.",
                    )
                }
        }
    }

    private suspend fun mapDefault(exchange: KtorExchange): FetcherResult<V> {
        val status = exchange.status
        return when {
            status == HttpStatusCode.PartialContent ->
                statusError(
                    exchange,
                    "HTTP 206 Partial Content cannot be adopted as a complete representation.",
                )

            status == HttpStatusCode.NoContent || status == HttpStatusCode.ResetContent ->
                statusError(
                    exchange,
                    "HTTP ${status.value} ${status.description} carries no representation to adopt; " +
                        "return a KtorOutcome from a KtorErrorMapper to handle it.",
                )

            status.value in 200..299 ->
                FetcherResult.Success(
                    value = decode(exchange.response),
                    etag =
                        encodeValidatorToken(
                            etagHeader = exchange.response.headers[HttpHeaders.ETag],
                            lastModifiedHeader = exchange.response.headers[HttpHeaders.LastModified],
                            lastModifiedFallback = lastModifiedFallback,
                        ),
                )

            status == HttpStatusCode.NotModified && exchange.conditional ->
                FetcherResult.NotModified(
                    selectNotModifiedValidatorToken(
                        etagHeader = exchange.response.headers[HttpHeaders.ETag],
                    ),
                )

            status == HttpStatusCode.NotModified ->
                statusError(
                    exchange,
                    "HTTP 304 Not Modified was received without a conditional request.",
                )

            status == HttpStatusCode.NotFound || status == HttpStatusCode.Gone ->
                when (notFoundPolicy) {
                    KtorNotFoundPolicy.Error -> statusError(exchange)
                    KtorNotFoundPolicy.Delete -> FetcherResult.Deleted
                }

            else -> statusError(exchange)
        }
    }

    /**
     * Refuses a 304 whose request did not carry exactly the one conditional header the kit wrote.
     *
     * `headers.remove` in the request builder reaches [configureRequest] and nothing later, so a
     * `defaultRequest` or another client plugin can append a second entity tag beside the kit's
     * validator, or add the other conditional header entirely. A server matching the foreign
     * validator answers 304, and adopting it would refresh the freshness of a resident value the
     * server never compared. [HttpResponse.request] carries the headers the request actually went
     * out with, which is the only place the plugin's contribution is visible to the kit.
     *
     * Returns null when the sent headers match, so the caller proceeds unchanged.
     */
    private fun foreignValidatorRefusal(
        exchange: KtorExchange,
        sentValidator: SentValidator,
    ): FetcherResult.Error? {
        val sentHeaders = exchange.response.request.headers
        val ifNoneMatch = sentHeaders.getAll(HttpHeaders.IfNoneMatch).orEmpty()
        val ifModifiedSince = sentHeaders.getAll(HttpHeaders.IfModifiedSince).orEmpty()
        val expected = listOf(sentValidator.value)
        val matches =
            when (sentValidator.name) {
                HttpHeaders.IfNoneMatch -> ifNoneMatch == expected && ifModifiedSince.isEmpty()
                HttpHeaders.IfModifiedSince -> ifModifiedSince == expected && ifNoneMatch.isEmpty()
                // Unreachable today: SentValidator has only two construction sites (in
                // fetch(), above), one per known header constant, so `name` is always one of
                // the two branches above. Kept for a future third validator — fail closed
                // rather than guess how to compare a header this function does not recognize.
                // The at-most-one-conditional-header invariant this guard depends on is
                // stated where sentValidator is chosen, in fetch().
                else -> false
            }
        if (matches) return null

        val carried =
            buildList {
                if (ifNoneMatch.isNotEmpty()) {
                    add("${HttpHeaders.IfNoneMatch}: ${ifNoneMatch.joinToString()}")
                }
                if (ifModifiedSince.isNotEmpty()) {
                    add("${HttpHeaders.IfModifiedSince}: ${ifModifiedSince.joinToString()}")
                }
            }.joinToString("; ").ifEmpty { "no conditional header" }
        return statusError(
            exchange,
            "The conditional request carried validators the kit did not set, so this HTTP 304 " +
                "Not Modified cannot be attributed to the recorded validator and freshness is " +
                "not refreshed. The kit set ${sentValidator.name}: ${sentValidator.value}; the " +
                "request went out with $carried. A client plugin such as defaultRequest " +
                "contributes headers after the kit's request builder, which cannot reach them.",
        )
    }

    private fun statusError(
        exchange: KtorExchange,
        message: String =
            "HTTP ${exchange.status.value} ${exchange.status.description} was not adopted.",
    ): FetcherResult.Error =
        FetcherResult.Error(
            KtorFetchException(
                status = exchange.status,
                method = exchange.method,
                url = exchange.url,
                message = "$message ${exchange.method.value} ${exchange.url}",
            ),
        )
}

/** The one conditional header the kit wrote for a request, recorded so a 304 can be attributed. */
private class SentValidator(
    val name: String,
    val value: String,
)
