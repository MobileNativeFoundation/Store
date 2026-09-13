@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.ktor

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The real-engine lane: a CIO client against an in-process CIO server on an ephemeral port.
 *
 * Every other test in this module runs on MockEngine, which short-circuits the transport. The four
 * behaviors here — mid-flight cancellation, a firing [HttpTimeout], a redirect hop, and
 * [HttpRequestRetry] composition — exist only below that short circuit, so they were previously
 * unverified in a kit whose whole job is HTTP.
 */
class KtorRealEngineTest {
    @Test
    fun cancelMidFlight_terminatesCancelledAndNeverReturnsAnError() =
        realEngineTest {
            val requestReceived = CompletableDeferred<Unit>()
            withServer({
                get(NEVER_RESPONDS) {
                    requestReceived.complete(Unit)
                    delay(FOREVER_MILLIS)
                    call.respondText("late")
                }
            }) { port ->
                HttpClient(io.ktor.client.engine.cio.CIO).use { client ->
                    val fetcher = realFetcher(client, port, NEVER_RESPONDS)
                    var returned: FetcherResult<String>? = null
                    var thrown: Throwable? = null

                    val deferred =
                        async {
                            try {
                                returned = fetcher.fetch(KEY, null)
                            } catch (failure: Throwable) {
                                thrown = failure
                                throw failure
                            }
                        }

                    requestReceived.await()
                    deferred.cancel()
                    runCatching { deferred.await() }

                    assertNull(
                        returned,
                        "a cancelled in-flight fetch must never produce a FetcherResult, " +
                            "least of all an Error that would record a fetch failure for the key",
                    )
                    assertIs<CancellationException>(thrown)
                }
            }
        }

    @Test
    fun httpTimeoutFiring_mapsToErrorPreservingTheKtorExceptionIdentity() =
        realEngineTest {
            withServer({
                get(NEVER_RESPONDS) {
                    delay(FOREVER_MILLIS)
                    call.respondText("late")
                }
            }) { port ->
                HttpClient(io.ktor.client.engine.cio.CIO) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = TIMEOUT_MILLIS
                    }
                }.use { client ->
                    val result =
                        assertIs<FetcherResult.Error>(
                            realFetcher(client, port, NEVER_RESPONDS).fetch(KEY, null),
                        )

                    // C07 asked whether a Ktor timeout arrives intact or wrapped. It arrives
                    // intact: the kit's broad catch arm hands the engine's own exception to
                    // FetcherResult.Error without rewrapping it as a KtorFetchException.
                    assertIs<HttpRequestTimeoutException>(result.cause)
                    assertFalse(result.cause is KtorFetchException)
                }
            }
        }

    @Test
    fun redirectHop_carriesTheConditionalHeaderToTheFinalRequest() =
        realEngineTest {
            val seen = CopyOnWriteArrayList<Pair<String, String?>>()
            withServer({
                get(MOVED) {
                    seen += MOVED to call.request.headers[HttpHeaders.IfNoneMatch]
                    call.respondRedirect(FINAL, permanent = false)
                }
                get(FINAL) {
                    seen += FINAL to call.request.headers[HttpHeaders.IfNoneMatch]
                    call.response.header(HttpHeaders.ETag, ETAG_V2)
                    call.respondText("payload")
                }
            }) { port ->
                HttpClient(io.ktor.client.engine.cio.CIO) {
                    followRedirects = true
                }.use { client ->
                    val result =
                        assertIs<FetcherResult.Success<String>>(
                            realFetcher(client, port, MOVED).fetch(KEY, ETAG_V1),
                        )

                    assertEquals("payload", result.value)
                    assertEquals(ETAG_V2, result.etag)
                    // README says "Ktor redirect handling carries conditional headers across
                    // redirects". This is that claim, executed: both hops carry the kit's
                    // validator, which is exactly why the README also tells callers that every
                    // redirect target must select the same representation.
                    assertEquals(
                        listOf(MOVED to ETAG_V1, FINAL to ETAG_V1),
                        seen.toList(),
                    )
                }
            }
        }

    @Test
    fun httpRequestRetry_sendsExactlyOneConditionalHeaderOnEveryAttempt() =
        realEngineTest {
            val attempts = CopyOnWriteArrayList<List<String>>()
            withServer({
                get(FLAKY) {
                    attempts += call.request.headers.getAll(HttpHeaders.IfNoneMatch).orEmpty()
                    if (attempts.size == 1) {
                        call.respond(HttpStatusCode.ServiceUnavailable)
                    } else {
                        call.response.header(HttpHeaders.ETag, ETAG_V2)
                        call.respondText("payload")
                    }
                }
            }) { port ->
                HttpClient(io.ktor.client.engine.cio.CIO) {
                    install(HttpRequestRetry) {
                        retryOnServerErrors(maxRetries = 1)
                        constantDelay(millis = 1, randomizationMs = 0)
                    }
                }.use { client ->
                    val result =
                        assertIs<FetcherResult.Success<String>>(
                            realFetcher(client, port, FLAKY).fetch(KEY, ETAG_V1),
                        )

                    assertEquals("payload", result.value)
                    assertEquals(ETAG_V2, result.etag)
                    // The retry plugin re-executes the request while `sentValidator` is computed
                    // once from the original builder. Each attempt must therefore carry exactly
                    // one If-None-Match, and the same one.
                    assertEquals(
                        listOf(listOf(ETAG_V1), listOf(ETAG_V1)),
                        attempts.toList(),
                    )
                }
            }
        }

    private fun realFetcher(
        client: HttpClient,
        port: Int,
        path: String,
        decode: suspend (HttpResponse) -> String = { response -> response.bodyAsText() },
    ): Fetcher<RealEngineKey, String> =
        ktorFetcher(
            client = client,
            decode = decode,
            configureRequest = configureRequest(port, path),
        )

    private fun configureRequest(
        port: Int,
        path: String,
    ): HttpRequestBuilder.(RealEngineKey) -> Unit =
        { _ -> url("http://127.0.0.1:$port$path") }

    private companion object {
        val KEY = RealEngineKey("1")
        const val NEVER_RESPONDS = "/never-responds"
        const val MOVED = "/moved"
        const val FINAL = "/final"
        const val FLAKY = "/flaky"
        const val ETAG_V1 = "\"v1\""
        const val ETAG_V2 = "\"v2\""
        const val TIMEOUT_MILLIS = 500L
        const val FOREVER_MILLIS = 600_000L
    }
}

private class RealEngineKey(
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("ktor-real-engine")

    override fun canonicalId(): String = id
}

/**
 * A wall-clock deadline for a suite that does real socket I/O and therefore cannot use virtual
 * time. A hang here fails the lane instead of stalling the build.
 */
private val SUITE_TIMEOUT = 60.seconds

private fun realEngineTest(body: suspend CoroutineScope.() -> Unit): Unit =
    runBlocking {
        withTimeout(SUITE_TIMEOUT) { body() }
    }

/**
 * Starts an in-process CIO server on port 0 and hands the block the port the OS actually bound, so
 * parallel runs never collide on a fixed port.
 */
private suspend fun withServer(
    routes: Routing.() -> Unit,
    block: suspend (Int) -> Unit,
) {
    val server =
        embeddedServer(io.ktor.server.cio.CIO, port = 0) {
            routing(routes)
        }
    server.start(wait = false)
    try {
        val port = server.engine.resolvedConnectors().first().port
        block(port)
    } finally {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }
}
