@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KtorFetcherTransportTest {
    @Test
    fun ok_withBodyAndEtag_successStoresEtagVerbatim() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "payload",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ETag, "\"v1\""),
                    )
                }

            HttpClient(engine).use { client ->
                val result = assertIs<FetcherResult.Success<String>>(transportFetcher(client).fetch(KEY, null))
                assertEquals("payload", result.value)
                assertEquals("\"v1\"", result.etag)
            }
        }

    @Test
    fun ok_onlyLastModified_successUsesLmToken() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "payload",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.LastModified, LM_DATE),
                    )
                }

            HttpClient(engine).use { client ->
                val result = assertIs<FetcherResult.Success<String>>(transportFetcher(client).fetch(KEY, null))
                assertEquals("payload", result.value)
                assertEquals("LM:$LM_DATE", result.etag)
            }
        }

    @Test
    fun ok_onlyLastModified_fallbackDisabled_successWithNullToken() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "payload",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.LastModified, LM_DATE),
                    )
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.Success<String>>(
                        transportFetcher(client, lastModifiedFallback = false).fetch(KEY, null),
                    )
                assertEquals("payload", result.value)
                assertNull(result.etag)
            }
        }

    @Test
    fun ok_bothValidators_etagWins() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "payload",
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ETag to listOf("\"v1\""),
                                HttpHeaders.LastModified to listOf(LM_DATE),
                            ),
                    )
                }

            HttpClient(engine).use { client ->
                val result = assertIs<FetcherResult.Success<String>>(transportFetcher(client).fetch(KEY, null))
                assertEquals("\"v1\"", result.etag)
            }
        }

    @Test
    fun partialContent_isErrorWithStatus() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "range", status = HttpStatusCode.PartialContent)
                }

            HttpClient(engine).use { client ->
                assertStatusError(
                    transportFetcher(client).fetch(KEY, null),
                    HttpStatusCode.PartialContent,
                )
            }
        }

    @Test
    fun ok_decodeThrows_errorPreservesOriginalExceptionType() =
        runTest {
            val decodeFailure = EmptyBodyException()
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.OK)
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.Error>(
                        transportFetcher(
                            client,
                            decode = { throw decodeFailure },
                        ).fetch(KEY, null),
                    )
                assertSame(decodeFailure, result.cause)
                assertFalse(result.cause is KtorFetchException)
            }
        }

    @Test
    fun noContent_decodeReturningValue_isNotAdoptedAsSuccess() =
        runTest {
            var decoded = false
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.NoContent)
                }

            HttpClient(engine).use { client ->
                val result =
                    transportFetcher(
                        client,
                        decode = { response ->
                            decoded = true
                            response.bodyAsText()
                        },
                    ).fetch(KEY, null)

                assertFalse(
                    result is FetcherResult.Success<*>,
                    "204 carries no representation and must not be adopted as Success",
                )
                assertFalse(decoded, "decode must not run for 204")
                assertStatusError(result, HttpStatusCode.NoContent)
            }
        }

    @Test
    fun resetContent_decodeReturningValue_isNotAdoptedAsSuccess() =
        runTest {
            var decoded = false
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.ResetContent)
                }

            HttpClient(engine).use { client ->
                val result =
                    transportFetcher(
                        client,
                        decode = { response ->
                            decoded = true
                            response.bodyAsText()
                        },
                    ).fetch(KEY, null)

                assertFalse(
                    result is FetcherResult.Success<*>,
                    "205 carries no representation and must not be adopted as Success",
                )
                assertFalse(decoded, "decode must not run for 205")
                assertStatusError(result, HttpStatusCode.ResetContent)
            }
        }

    @Test
    fun noContent_errorMapperMayStillOverrideTheRefusal() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.NoContent)
                }

            HttpClient(engine).use { client ->
                assertEquals(
                    FetcherResult.Deleted,
                    transportFetcher(
                        client,
                        errorMapper =
                            KtorErrorMapper { exchange ->
                                if (exchange.status == HttpStatusCode.NoContent) {
                                    KtorOutcome.Delete
                                } else {
                                    KtorOutcome.Defer
                                }
                            },
                    ).fetch(KEY, null),
                )
            }
        }

    @Test
    fun conditionalEtag_sendsIfNoneMatchOnly_304adoptsResponseEtag() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals(listOf("\"v1\""), request.headers.getAll(HttpHeaders.IfNoneMatch))
                    assertNoHeader(request, HttpHeaders.IfModifiedSince)
                    respond(
                        content = "",
                        status = HttpStatusCode.NotModified,
                        headers = headersOf(HttpHeaders.ETag, "\"v2\""),
                    )
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.NotModified>(
                        transportFetcher(client).fetch(KEY, "\"v1\""),
                    )
                assertEquals("\"v2\"", result.etag)
            }
        }

    @Test
    fun conditionalLastModified_sendsIfModifiedSinceOnly_304withoutEtagIsNoFlip() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals(listOf(LM_DATE), request.headers.getAll(HttpHeaders.IfModifiedSince))
                    assertNoHeader(request, HttpHeaders.IfNoneMatch)
                    respond(
                        content = "",
                        status = HttpStatusCode.NotModified,
                        headers = headersOf(HttpHeaders.LastModified, LM_DATE),
                    )
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.NotModified>(
                        transportFetcher(client).fetch(KEY, "LM:$LM_DATE"),
                    )
                assertNull(result.etag)
            }
        }

    @Test
    fun unconditional304_isErrorWithNotModifiedStatus() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertNoConditionalHeaders(request)
                    respond(content = "", status = HttpStatusCode.NotModified)
                }

            HttpClient(engine).use { client ->
                assertStatusError(
                    transportFetcher(client).fetch(KEY, null),
                    HttpStatusCode.NotModified,
                )
            }
        }

    @Test
    fun post_doesNotSendConditionalHeaders_304isAnomalyError() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    assertNoConditionalHeaders(request)
                    respond(content = "", status = HttpStatusCode.NotModified)
                }

            HttpClient(engine).use { client ->
                assertStatusError(
                    transportFetcher(
                        client,
                        configureRequest = { key ->
                            method = HttpMethod.Post
                            url("https://example.test/items/${key.canonicalId()}")
                        },
                    ).fetch(KEY, "\"v1\""),
                    HttpStatusCode.NotModified,
                )
            }
        }

    @Test
    fun head_sendsIfNoneMatchOnly_304adoptsResponseEtag() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals(HttpMethod.Head, request.method)
                    assertEquals(listOf("\"v1\""), request.headers.getAll(HttpHeaders.IfNoneMatch))
                    assertNoHeader(request, HttpHeaders.IfModifiedSince)
                    respond(
                        content = "",
                        status = HttpStatusCode.NotModified,
                        headers = headersOf(HttpHeaders.ETag, "\"v2\""),
                    )
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.NotModified>(
                        transportFetcher(
                            client,
                            configureRequest = { key ->
                                method = HttpMethod.Head
                                url("https://example.test/items/${key.canonicalId()}")
                            },
                        ).fetch(KEY, "\"v1\""),
                    )
                assertEquals("\"v2\"", result.etag)
            }
        }

    @Test
    fun replaceNotAppend_etagPlan_keepsExactlyOneIfNoneMatch() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals(listOf("\"v1\""), request.headers.getAll(HttpHeaders.IfNoneMatch))
                    assertNoHeader(request, HttpHeaders.IfModifiedSince)
                    respond(
                        content = "",
                        status = HttpStatusCode.NotModified,
                        headers = headersOf(HttpHeaders.ETag, "\"v1\""),
                    )
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.NotModified>(
                        transportFetcher(client, configureRequest = staleConditionalHeaders()).fetch(KEY, "\"v1\""),
                    )
                assertEquals("\"v1\"", result.etag)
            }
        }

    @Test
    fun replaceNotAppend_nullEtag_stripsCallerConditionalHeaders() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertNoConditionalHeaders(request)
                    respond(
                        content = "payload",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ETag, "\"fresh\""),
                    )
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.Success<String>>(
                        transportFetcher(client, configureRequest = staleConditionalHeaders()).fetch(KEY, null),
                    )
                assertEquals("payload", result.value)
                assertEquals("\"fresh\"", result.etag)
            }
        }

    @Test
    fun notFound_defaultPolicy_isError() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.NotFound)
                }

            HttpClient(engine).use { client ->
                assertStatusError(
                    transportFetcher(client).fetch(KEY, null),
                    HttpStatusCode.NotFound,
                )
            }
        }

    @Test
    fun notFound_deletePolicy_isDeleted() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.NotFound)
                }

            HttpClient(engine).use { client ->
                assertEquals(
                    FetcherResult.Deleted,
                    transportFetcher(client, notFoundPolicy = KtorNotFoundPolicy.Delete).fetch(KEY, null),
                )
            }
        }

    @Test
    fun gone_defaultPolicy_isError() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.Gone)
                }

            HttpClient(engine).use { client ->
                assertStatusError(
                    transportFetcher(client).fetch(KEY, null),
                    HttpStatusCode.Gone,
                )
            }
        }

    @Test
    fun gone_deletePolicy_isDeleted() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.Gone)
                }

            HttpClient(engine).use { client ->
                assertEquals(
                    FetcherResult.Deleted,
                    transportFetcher(client, notFoundPolicy = KtorNotFoundPolicy.Delete).fetch(KEY, null),
                )
            }
        }

    @Test
    fun serverError_preservesStatus() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.InternalServerError)
                }

            HttpClient(engine).use { client ->
                assertStatusError(
                    transportFetcher(client).fetch(KEY, null),
                    HttpStatusCode.InternalServerError,
                )
            }
        }

    @Test
    fun redirectOtherThan304_isError() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "",
                        status = HttpStatusCode.MovedPermanently,
                        headers = headersOf(HttpHeaders.Location, "https://example.test/elsewhere"),
                    )
                }

            HttpClient(engine) {
                followRedirects = false
            }.use { client ->
                assertStatusError(
                    transportFetcher(client).fetch(KEY, null),
                    HttpStatusCode.MovedPermanently,
                )
            }
        }

    @Test
    fun transportFailure_preservesOriginalException() =
        runTest {
            val boom = TransportIoException()
            val engine = MockEngine { throw boom }

            HttpClient(engine).use { client ->
                val result = assertIs<FetcherResult.Error>(transportFetcher(client).fetch(KEY, null))
                assertSame(boom, result.cause)
                assertFalse(result.cause is KtorFetchException)
            }
        }

    @Test
    fun cancellation_propagatesAndDoesNotReturnError() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    started.complete(Unit)
                    delay(60_000)
                    respond(content = "late", status = HttpStatusCode.OK)
                }

            HttpClient(engine).use { client ->
                val fetcher = transportFetcher(client)
                var returned: FetcherResult<String>? = null
                val deferred =
                    async {
                        fetcher.fetch(KEY, null).also { result -> returned = result }
                    }
                started.await()
                deferred.cancel()

                // Deferred.await rethrows the terminal cause, so this assertion has teeth where
                // Job.join's did not: join resumes normally for a cancelled job.
                assertFailsWith<CancellationException> { deferred.await() }
                assertNull(returned, "a cancelled fetch must not produce a FetcherResult")
            }
        }

    @Test
    fun engineThrownCancellation_propagatesInsteadOfBecomingError() =
        runTest {
            // Negative control for the `catch (CancellationException)` rethrow arm: delete that
            // arm and this fetch returns FetcherResult.Error instead of failing.
            val engine =
                MockEngine {
                    throw CancellationException("the engine cancelled the call")
                }

            HttpClient(engine).use { client ->
                val fetcher = transportFetcher(client)
                var returned: FetcherResult<String>? = null

                assertFailsWith<CancellationException> {
                    returned = fetcher.fetch(KEY, null)
                }
                assertNull(returned, "a cancellation must never be adopted as a FetcherResult")
            }
        }

    @Test
    fun engineSurfacedCancellation_producesNoFetcherResult() =
        runTest {
            // Documents the scenario; it is NOT the control for the `ensureActive()` call in the
            // kit's broad catch arm, and the name no longer claims to be. MockEngine's throw
            // crosses a suspension point in the request pipeline with the job already cancelled,
            // so the pipeline converts it to a CancellationException before the kit's catch arms
            // see it — this passes with or without `ensureActive()`. The real control is
            // `nonCancellationFailureAfterCancellation_isNotRecordedAsFetchError` below, which
            // raises the failure synchronously inside `configureRequest` so it reaches the broad
            // arm as its own type.
            var returned: FetcherResult<String>? = null
            var thrown: Throwable? = null
            lateinit var deferred: Deferred<Unit>
            val engine =
                MockEngine {
                    // Some engines report a cancelled call as their own exception type rather than
                    // as a CancellationException (Darwin's NSURLErrorCancelled, OkHttp's
                    // IOException("Canceled"), the JS AbortError). Cancel first, then surface the
                    // engine's own type, and the kit must still stand down rather than record a
                    // fetch failure.
                    deferred.cancel()
                    throw EngineSurfacedCancellation()
                }

            HttpClient(engine).use { client ->
                val fetcher = transportFetcher(client)
                deferred =
                    async(start = CoroutineStart.LAZY) {
                        try {
                            returned = fetcher.fetch(KEY, null)
                        } catch (failure: Throwable) {
                            thrown = failure
                            throw failure
                        }
                    }

                runCatching { deferred.await() }

                assertNull(returned, "a cancelled fetch must not produce a FetcherResult")
                assertIs<CancellationException>(
                    thrown,
                    "an engine-surfaced cancellation must be rethrown as cancellation",
                )
            }
        }

    @Test
    fun nonCancellationFailureAfterCancellation_isNotRecordedAsFetchError() =
        runTest {
            var returned: FetcherResult<String>? = null
            var thrown: Throwable? = null
            lateinit var deferred: Deferred<Unit>
            lateinit var originalFailure: EngineSurfacedCancellation
            val engine =
                MockEngine {
                    respond(content = "payload", status = HttpStatusCode.OK)
                }

            HttpClient(engine).use { client ->
                // Negative control for `ensureActive()` in the broad catch arm. The failure is
                // raised synchronously inside the fetch, with the job already cancelled, so it
                // reaches that arm as its own type rather than being converted to a
                // CancellationException by a suspension point on the way. Without ensureActive()
                // the arm records FetcherResult.Error for a coroutine that is no longer alive.
                val fetcher =
                    transportFetcher(
                        client,
                        configureRequest = { key ->
                            url("https://example.test/items/${key.canonicalId()}")
                            deferred.cancel()
                            originalFailure = EngineSurfacedCancellation()
                            throw originalFailure
                        },
                    )
                deferred =
                    async(start = CoroutineStart.LAZY) {
                        try {
                            returned = fetcher.fetch(KEY, null)
                        } catch (failure: Throwable) {
                            thrown = failure
                            throw failure
                        }
                    }

                runCatching { deferred.await() }

                assertNull(returned, "a cancelled fetch must not produce a FetcherResult")
                val rethrown =
                    assertIs<CancellationException>(
                        thrown,
                        "a failure raised after cancellation must be rethrown as cancellation",
                    )
                // The `ensureActive()` throw replaces `failure` in the control flow, so without
                // attaching it, the original non-cancellation failure's information is silently
                // lost. It must survive as a suppressed exception on the rethrown cancellation.
                assertSame(
                    originalFailure,
                    rethrown.suppressedExceptions.singleOrNull(),
                    "the original failure must be attached to the rethrown cancellation via addSuppressed",
                )
            }
        }

    @Test
    fun clientDefaultRequestConditionalHeader_reachesTheEngineAndBreaksTheFetch() =
        runTest {
            // The kit's strip is builder-scoped: `headers.remove` inside `prepareRequest` cannot
            // reach a header a client plugin contributes later in the request pipeline. Observed
            // on Ktor 3.5.2: the DefaultRequest header reaches the engine, the kit's own
            // `conditional` flag stays false, and the resulting 304 is read as a protocol anomaly.
            // That is a hard failure on every request, with a message pointing at the wrong thing,
            // which is why the contract has to name `defaultRequest` and plugins explicitly.
            val seenIfNoneMatch = mutableListOf<List<String>?>()
            val engine =
                MockEngine { request ->
                    seenIfNoneMatch += request.headers.getAll(HttpHeaders.IfNoneMatch)
                    respond(content = "", status = HttpStatusCode.NotModified)
                }

            HttpClient(engine) {
                defaultRequest { header(HttpHeaders.IfNoneMatch, "\"x\"") }
            }.use { client ->
                val result = transportFetcher(client).fetch(KEY, null)

                assertEquals(listOf<List<String>?>(listOf("\"x\"")), seenIfNoneMatch)
                assertStatusError(result, HttpStatusCode.NotModified)
            }
        }

    @Test
    fun clientDefaultRequestConditionalHeader_besideTheKitsValidator_isRefused() =
        runTest {
            val seenIfNoneMatch = mutableListOf<List<String>?>()
            val engine =
                MockEngine { request ->
                    seenIfNoneMatch += request.headers.getAll(HttpHeaders.IfNoneMatch)
                    respond(
                        content = "",
                        status = HttpStatusCode.NotModified,
                        headers = headersOf(HttpHeaders.ETag, "\"v2\""),
                    )
                }

            HttpClient(engine) {
                defaultRequest { header(HttpHeaders.IfNoneMatch, "\"x\"") }
            }.use { client ->
                val result = transportFetcher(client).fetch(KEY, "\"v1\"")

                // Merge order, observed on Ktor 3.5.2: DefaultRequest appends beside the kit's
                // validator rather than yielding to it, so the request carries two entity tags.
                // The kit's replace-not-append guarantee holds only against `configureRequest`.
                // A red on this assertion means Ktor's precedence changed: re-read the contract
                // and re-derive what the kit can promise, rather than patching the kit to match.
                assertEquals(listOf<List<String>?>(listOf("\"v1\"", "\"x\"")), seenIfNoneMatch)

                // A server matching the plugin's tag answers 304 for a representation the kit
                // never asked about, and adopting it would refresh the freshness of a resident
                // value recorded under a different tag. The kit compares the conditional headers
                // the request actually carried against the single one it wrote, and refuses.
                // The message is asserted so that a regression turning `conditional` false —
                // which would refuse with the unrelated case-1 anomaly — cannot pass this test.
                val refusal = assertStatusError(result, HttpStatusCode.NotModified)
                assertTrue(
                    refusal.message.orEmpty().contains(FOREIGN_VALIDATOR_REFUSAL),
                    "expected the foreign-validator refusal, was ${refusal.message}",
                )
            }
        }

    @Test
    fun clientDefaultRequestForeignIfModifiedSince_besideTheKitsEtag_isRefused() =
        runTest {
            // The foreign validator need not be the same header the kit wrote. Here the kit sends
            // If-None-Match from its recorded ETag and the plugin adds If-Modified-Since, so a
            // server may answer 304 on the date alone while the entity tag never matched.
            val seenConditionals = mutableListOf<Pair<List<String>?, List<String>?>>()
            val engine =
                MockEngine { request ->
                    seenConditionals +=
                        request.headers.getAll(HttpHeaders.IfNoneMatch) to
                        request.headers.getAll(HttpHeaders.IfModifiedSince)
                    respond(content = "", status = HttpStatusCode.NotModified)
                }

            HttpClient(engine) {
                defaultRequest { header(HttpHeaders.IfModifiedSince, LM_DATE) }
            }.use { client ->
                val result = transportFetcher(client).fetch(KEY, "\"v1\"")

                assertEquals(
                    listOf<Pair<List<String>?, List<String>?>>(
                        listOf("\"v1\"") to listOf(LM_DATE),
                    ),
                    seenConditionals,
                )
                val refusal = assertStatusError(result, HttpStatusCode.NotModified)
                assertTrue(
                    refusal.message.orEmpty().contains(FOREIGN_VALIDATOR_REFUSAL),
                    "expected the foreign-validator refusal, was ${refusal.message}",
                )
            }
        }

    @Test
    fun clientDefaultRequestConditionalHeader_besideTheKitsLastModified_isRefused() =
        runTest {
            // Mirrors clientDefaultRequestConditionalHeader_besideTheKitsValidator_isRefused on
            // the Last-Modified path: the kit's own conditional header is If-Modified-Since here,
            // so the plugin's append produces two If-Modified-Since values instead of two entity
            // tags.
            val seenIfModifiedSince = mutableListOf<List<String>?>()
            val engine =
                MockEngine { request ->
                    seenIfModifiedSince += request.headers.getAll(HttpHeaders.IfModifiedSince)
                    respond(content = "", status = HttpStatusCode.NotModified)
                }

            HttpClient(engine) {
                defaultRequest { header(HttpHeaders.IfModifiedSince, FOREIGN_LM_DATE) }
            }.use { client ->
                val result = transportFetcher(client).fetch(KEY, "LM:$LM_DATE")

                assertEquals(
                    listOf<List<String>?>(listOf(LM_DATE, FOREIGN_LM_DATE)),
                    seenIfModifiedSince,
                )

                val refusal = assertStatusError(result, HttpStatusCode.NotModified)
                assertTrue(
                    refusal.message.orEmpty().contains(FOREIGN_VALIDATOR_REFUSAL),
                    "expected the foreign-validator refusal, was ${refusal.message}",
                )
            }
        }

    @Test
    fun requestPipelineInterceptor_removesTheKitsIfNoneMatch_isRefused() =
        runTest {
            // defaultRequest cannot remove a header the kit's builder already set - it can only
            // contribute alongside it (see the two tests above) - so the missing-header case is
            // driven through a request pipeline interceptor instead. Installed at the State phase,
            // it runs after both prepareRequest's block and the Before phase defaultRequest uses,
            // so it observes and can strip the kit's own header. The sent request then carries no
            // conditional header at all, which the guard finds just as unattributable as a foreign
            // one.
            val engine =
                MockEngine { request ->
                    assertNoHeader(request, HttpHeaders.IfNoneMatch)
                    respond(content = "", status = HttpStatusCode.NotModified)
                }

            val client = HttpClient(engine)
            client.requestPipeline.intercept(HttpRequestPipeline.State) {
                context.headers.remove(HttpHeaders.IfNoneMatch)
            }

            client.use {
                val result = transportFetcher(client).fetch(KEY, "\"v1\"")

                val refusal = assertStatusError(result, HttpStatusCode.NotModified)
                assertTrue(
                    refusal.message.orEmpty().contains(FOREIGN_VALIDATOR_REFUSAL),
                    "expected the foreign-validator refusal, was ${refusal.message}",
                )
            }
        }

    @Test
    fun errorMapper_failOnOk_skipsDecode() =
        runTest {
            var decoded = false
            val custom =
                KtorFetchException(
                    status = HttpStatusCode.BadRequest,
                    method = HttpMethod.Get,
                    url = "https://example.test/mapped",
                    message = "mapper fail",
                )
            val engine =
                MockEngine {
                    respond(content = "payload", status = HttpStatusCode.OK)
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.Error>(
                        transportFetcher(
                            client,
                            errorMapper = KtorErrorMapper { KtorOutcome.Fail(custom) },
                            decode = {
                                decoded = true
                                it.bodyAsText()
                            },
                        ).fetch(KEY, null),
                    )
                assertSame(custom, result.cause)
                assertFalse(decoded)
            }
        }

    @Test
    fun errorMapper_defer_appliesDefaultTable() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.InternalServerError)
                }

            HttpClient(engine).use { client ->
                assertStatusError(
                    transportFetcher(
                        client,
                        errorMapper = KtorErrorMapper { KtorOutcome.Defer },
                    ).fetch(KEY, null),
                    HttpStatusCode.InternalServerError,
                )
            }
        }

    @Test
    fun errorMapper_notModifiedOnUnconditionalExchange_isRejected() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.NotModified)
                }

            HttpClient(engine).use { client ->
                val result =
                    transportFetcher(
                        client,
                        errorMapper = NotModifiedOverride,
                    ).fetch(KEY, null)

                assertFalse(
                    result is FetcherResult.NotModified,
                    "a mapper must not refresh freshness for an exchange that sent no validator",
                )
                assertStatusError(result, HttpStatusCode.NotModified)
            }
        }

    @Test
    fun errorMapper_notModifiedOnUnconditionalNon304_isRejected() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.InternalServerError)
                }

            HttpClient(engine).use { client ->
                val result =
                    transportFetcher(
                        client,
                        errorMapper = KtorErrorMapper { KtorOutcome.NotModified(null) },
                    ).fetch(KEY, null)

                assertFalse(
                    result is FetcherResult.NotModified,
                    "a mapper must not refresh freshness from a status the kit never validated",
                )
                assertStatusError(result, HttpStatusCode.InternalServerError)
            }
        }

    @Test
    fun errorMapper_notModifiedOnConditionalExchange_overridesValidatorToken() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals(listOf("\"v1\""), request.headers.getAll(HttpHeaders.IfNoneMatch))
                    respond(content = "", status = HttpStatusCode.NotModified)
                }

            HttpClient(engine).use { client ->
                val result =
                    assertIs<FetcherResult.NotModified>(
                        transportFetcher(
                            client,
                            errorMapper = NotModifiedOverride,
                        ).fetch(KEY, "\"v1\""),
                    )
                assertEquals("\"override\"", result.etag)
            }
        }

    @Test
    fun errorMapper_deleteOnNotFound_isDeleted() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.NotFound)
                }

            HttpClient(engine).use { client ->
                assertEquals(
                    FetcherResult.Deleted,
                    transportFetcher(
                        client,
                        errorMapper = KtorErrorMapper { KtorOutcome.Delete },
                    ).fetch(KEY, null),
                )
            }
        }

    private fun transportFetcher(
        client: HttpClient,
        notFoundPolicy: KtorNotFoundPolicy = KtorNotFoundPolicy.Error,
        lastModifiedFallback: Boolean = true,
        errorMapper: KtorErrorMapper = KtorErrorMapper.Default,
        decode: suspend (HttpResponse) -> String = DefaultDecode,
        configureRequest: HttpRequestBuilder.(TransportKey) -> Unit = DefaultConfigure,
    ): Fetcher<TransportKey, String> =
        ktorFetcher(
            client = client,
            notFoundPolicy = notFoundPolicy,
            lastModifiedFallback = lastModifiedFallback,
            errorMapper = errorMapper,
            decode = decode,
            configureRequest = configureRequest,
        )

    private fun staleConditionalHeaders(): HttpRequestBuilder.(TransportKey) -> Unit =
        { key ->
            url("https://example.test/items/${key.canonicalId()}")
            headers.append(HttpHeaders.IfNoneMatch, "stale")
            headers.append(HttpHeaders.IfModifiedSince, LM_DATE)
        }

    private fun assertStatusError(
        result: FetcherResult<String>,
        status: HttpStatusCode,
    ): KtorFetchException {
        val error = assertIs<FetcherResult.Error>(result)
        val cause = assertIs<KtorFetchException>(error.cause)
        assertEquals(status, cause.status)
        return cause
    }

    private fun assertNoConditionalHeaders(request: HttpRequestData) {
        assertNoHeader(request, HttpHeaders.IfNoneMatch)
        assertNoHeader(request, HttpHeaders.IfModifiedSince)
    }

    private fun assertNoHeader(
        request: HttpRequestData,
        name: String,
    ) {
        val values = request.headers.getAll(name)
        assertTrue(values.isNullOrEmpty(), "expected no $name, found $values")
    }

    private companion object {
        val KEY = TransportKey("1")
        const val LM_DATE = "Wed, 21 Oct 2015 07:28:00 GMT"
        const val FOREIGN_LM_DATE = "Thu, 22 Oct 2015 07:28:00 GMT"
        const val FOREIGN_VALIDATOR_REFUSAL = "carried validators the kit did not set"
        val DefaultDecode: suspend (HttpResponse) -> String = { response ->
            val text = response.bodyAsText()
            if (text.isEmpty()) throw EmptyBodyException()
            text
        }
        val DefaultConfigure: HttpRequestBuilder.(TransportKey) -> Unit = { key ->
            url("https://example.test/items/${key.canonicalId()}")
        }
        val NotModifiedOverride: KtorErrorMapper =
            KtorErrorMapper { exchange ->
                if (exchange.status == HttpStatusCode.NotModified) {
                    KtorOutcome.NotModified("\"override\"")
                } else {
                    KtorOutcome.Defer
                }
            }
    }
}

private class TransportKey(
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("ktor-transport")

    override fun canonicalId(): String = id
}

private class EmptyBodyException : IllegalStateException("empty body")

private class TransportIoException : Exception("io failure")

private class EngineSurfacedCancellation : Exception("the engine reports the call was cancelled")
