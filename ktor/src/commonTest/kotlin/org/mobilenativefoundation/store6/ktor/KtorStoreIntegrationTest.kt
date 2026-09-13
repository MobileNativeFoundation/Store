@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.ktor

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.yield
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.testing.FakeSourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class KtorStoreIntegrationTest {
    @Test
    fun invalidateThenConditionalRefetch_emitsOneRevalidatedAndClearsStaleness() = runTest {
        var requests = 0
        var invalidateReturned = false
        val ifNoneMatchHeaders = mutableListOf<String?>()
        val revalidationHeaders = mutableListOf<String?>()
        val engine =
            MockEngine { request ->
                val ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
                ifNoneMatchHeaders += ifNoneMatch
                if (invalidateReturned) {
                    revalidationHeaders += ifNoneMatch
                }
                when (++requests) {
                    1 ->
                        respond(
                            content = "v1",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ETag, "\"v1\""),
                        )

                    else -> respond(content = "", status = HttpStatusCode.NotModified)
                }
            }

        HttpClient(engine).use { client ->
            val store =
                store<IntegrationKey, String> {
                    ktorFetcher(
                        client = client,
                        decode = { response -> response.bodyAsText() },
                        configureRequest = { key ->
                            url("https://example.test/items/${key.canonicalId()}")
                        },
                    )
                }
            val key = IntegrationKey("revalidation")
            try {
                assertEquals("v1", store.get(key))
                assertEquals(1, requests, "the cold read must issue exactly one request")
                store.invalidate(key)
                invalidateReturned = true

                store.stream(key).test {
                    var revalidatedCount = 0
                    while (revalidatedCount == 0) {
                        when (val result = awaitItem()) {
                            is StoreResult.Data -> {
                                assertEquals("v1", result.value)
                                assertTrue(result.isStale)
                            }

                            is StoreResult.Revalidated -> revalidatedCount += 1
                            is StoreResult.Error ->
                                fail("unexpected Store error: ${result.error}")

                            is StoreResult.Loading ->
                                fail("resident invalidation must not emit Loading")
                        }
                    }

                    assertEquals(1, revalidatedCount)
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }

                // Documented core-owned nondeterminism: the engine may issue one obsolete
                // cold-baseline launch that the 304 cycle then self-heals. An exact count of
                // two held on JVM, macOS, JS, and wasm but was observed as three on linuxX64,
                // iosSimulatorArm64, and the Android unit lane (PR #77, first CI attempt), so
                // the count is tolerated and the guarantees below are asserted instead.
                assertTrue(
                    revalidationHeaders.isNotEmpty() &&
                        revalidationHeaders.size <= 2 &&
                        revalidationHeaders.all { it == "\"v1\"" },
                    "invalidation must issue one or two conditional revalidation requests, each " +
                        "carrying the stored validator; observed $revalidationHeaders",
                )
                assertTrue(
                    requests in 2..3,
                    "the 304 cycle may self-heal one obsolete cold-baseline launch; observed " +
                        "ifNoneMatchHeaders=$ifNoneMatchHeaders",
                )
                assertNull(ifNoneMatchHeaders[0])
                ifNoneMatchHeaders.drop(1).forEach { header ->
                    assertEquals("\"v1\"", header)
                }
                val requestsAfterRevalidated = requests
                assertEquals("v1", store.get(key))
                assertEquals(requestsAfterRevalidated, requests)
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun trulyColdNotModified_mapperOverrideIsRejected() = runTest {
        val engine =
            MockEngine { request ->
                assertNull(request.headers[HttpHeaders.IfNoneMatch])
                assertNull(request.headers[HttpHeaders.IfModifiedSince])
                respond(content = "", status = HttpStatusCode.NotModified)
            }

        HttpClient(engine).use { client ->
            val store =
                store<IntegrationKey, String> {
                    ktorFetcher(
                        client = client,
                        errorMapper = UnconditionalNotModifiedOverride,
                        decode = { response -> response.bodyAsText() },
                        configureRequest = { key ->
                            url("https://example.test/items/${key.canonicalId()}")
                        },
                    )
                }
            try {
                assertFetchStatus(store, IntegrationKey("cold-304"), HttpStatusCode.NotModified)
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun mapperNotModifiedWithoutConditionalRequest_leavesStaleValueStale() = runTest {
        var requests = 0
        val engine =
            MockEngine {
                when (++requests) {
                    // No ETag and no Last-Modified: the resident value carries no validator, so
                    // every later request is unconditional.
                    1 -> respond(content = "v1", status = HttpStatusCode.OK)
                    else -> respond(content = "", status = HttpStatusCode.NotModified)
                }
            }

        HttpClient(engine).use { client ->
            val store =
                store<IntegrationKey, String> {
                    ktorFetcher(
                        client = client,
                        errorMapper = UnconditionalNotModifiedOverride,
                        decode = { response -> response.bodyAsText() },
                        configureRequest = { key ->
                            url("https://example.test/items/${key.canonicalId()}")
                        },
                    )
                }
            val key = IntegrationKey("stale-stays-stale")
            try {
                assertEquals("v1", store.get(key))
                store.invalidate(key)

                assertFetchStatus(store, key, HttpStatusCode.NotModified, Freshness.MustBeFresh)

                store.stream(key, Freshness.LocalOnly).test {
                    val data = assertIs<StoreResult.Data<String>>(awaitItem())
                    assertEquals("v1", data.value)
                    assertTrue(
                        data.isStale,
                        "an unvalidated NotModified must not mark the stale value fresh",
                    )
                    cancelAndIgnoreRemainingEvents()
                }
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun noContent_doesNotReplaceResidentValueAndDoesNotMarkItFresh() = runTest {
        var requests = 0
        val engine =
            MockEngine {
                when (++requests) {
                    1 ->
                        respond(
                            content = "v1",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ETag, "\"v1\""),
                        )

                    else -> respond(content = "", status = HttpStatusCode.NoContent)
                }
            }

        HttpClient(engine).use { client ->
            val store =
                store<IntegrationKey, String> {
                    ktorFetcher(
                        client = client,
                        decode = { response -> response.bodyAsText() },
                        configureRequest = { key ->
                            url("https://example.test/items/${key.canonicalId()}")
                        },
                    )
                }
            val key = IntegrationKey("no-content")
            try {
                assertEquals("v1", store.get(key))
                store.invalidate(key)

                assertFetchStatus(store, key, HttpStatusCode.NoContent, Freshness.MustBeFresh)

                store.stream(key, Freshness.LocalOnly).test {
                    val data = assertIs<StoreResult.Data<String>>(awaitItem())
                    assertEquals("v1", data.value)
                    assertTrue(data.isStale, "a refused 204 must not mark the resident value fresh")
                    cancelAndIgnoreRemainingEvents()
                }
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun mustBeFresh_reRequestsInsteadOfServingResident() = runTest {
        var requests = 0
        val engine =
            MockEngine {
                requests += 1
                respond(content = "v$requests", status = HttpStatusCode.OK)
            }

        HttpClient(engine).use { client ->
            val store =
                store<IntegrationKey, String> {
                    ktorFetcher(
                        client = client,
                        decode = { response -> response.bodyAsText() },
                        configureRequest = { key ->
                            url("https://example.test/items/${key.canonicalId()}")
                        },
                    )
                }
            val key = IntegrationKey("freshness")
            try {
                assertEquals("v1", store.get(key))
                assertEquals("v2", store.get(key, Freshness.MustBeFresh))
                assertEquals(2, requests)
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun serverError_surfacesTypedKtorFetchException() = runTest {
        val engine =
            MockEngine {
                respond(content = "failure", status = HttpStatusCode.InternalServerError)
            }

        HttpClient(engine).use { client ->
            val store =
                store<IntegrationKey, String> {
                    ktorFetcher(
                        client = client,
                        decode = { response -> response.bodyAsText() },
                        configureRequest = { key ->
                            url("https://example.test/items/${key.canonicalId()}")
                        },
                    )
                }
            try {
                val cause =
                    assertFetchStatus(
                        store,
                        IntegrationKey("typed-error"),
                        HttpStatusCode.InternalServerError,
                    )

                assertEquals(HttpMethod.Get, cause.method)
                assertEquals("https://example.test/items/typed-error", cause.url)
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun postEvictionHydration_fetchesWithoutResidentValidator() = runTest {
        val conditionalHeaders = mutableListOf<Pair<String?, String?>>()
        val engine =
            MockEngine { request ->
                conditionalHeaders +=
                    request.headers[HttpHeaders.IfNoneMatch] to
                    request.headers[HttpHeaders.IfModifiedSince]
                val requestNumber = conditionalHeaders.size
                respond(
                    content = "v$requestNumber",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ETag, "\"v$requestNumber\""),
                )
            }

        HttpClient(engine).use { client ->
            val store =
                store<IntegrationKey, String> {
                    maxIdleKeys(0)
                    persistence(FakeSourceOfTruth())
                    ktorFetcher(
                        client = client,
                        decode = { response -> response.bodyAsText() },
                        configureRequest = { key ->
                            url("https://example.test/items/${key.canonicalId()}")
                        },
                    )
                }
            val key = IntegrationKey("validator-lifetime")
            try {
                assertEquals("v1", store.get(key))
                var origin: Origin? = null
                var polls = 0
                while (origin != Origin.SOT) {
                    // Bounded: yield() does not advance virtual time, so an unbounded spin would
                    // turn a residence regression into a 25-second runTest timeout instead of a
                    // legible failure.
                    if (polls++ == MAX_EVICTION_POLLS) {
                        fail(
                            "the value never left residence: after $MAX_EVICTION_POLLS polls its " +
                                "origin was still $origin, expected ${Origin.SOT}",
                        )
                    }
                    store.stream(key, Freshness.LocalOnly).test {
                        origin = assertIs<StoreResult.Data<String>>(awaitItem()).origin
                        cancelAndIgnoreRemainingEvents()
                    }
                    if (origin != Origin.SOT) yield()
                }
                assertEquals("v2", store.get(key, Freshness.MustBeFresh))
                val expectedHeaders =
                    listOf<Pair<String?, String?>>(null to null, null to null)
                assertEquals(expectedHeaders, conditionalHeaders)
            } finally {
                store.close()
            }
        }
    }
}

// Generous: eviction normally lands on the first poll. This only has to beat the 25s shadow with
// a readable message.
private const val MAX_EVICTION_POLLS = 1_000

private val UnconditionalNotModifiedOverride =
    KtorErrorMapper { exchange ->
        if (exchange.status == HttpStatusCode.NotModified) {
            KtorOutcome.NotModified(null)
        } else {
            KtorOutcome.Defer
        }
    }

private class IntegrationKey(private val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("ktor-integration")

    override fun canonicalId(): String = id
}

/**
 * Asserts that reading [key] fails with HTTP [status], unwrapping the whole refusal chain:
 * `StoreException` to `StoreError.Fetch` to `KtorFetchException`.
 *
 * The transport suite's `assertStatusError` does the same for a raw `FetcherResult`; this is the
 * store-level counterpart, and like that one it returns the typed exception so a caller that needs
 * more than the status can go on asserting.
 */
private suspend fun assertFetchStatus(
    store: Store<IntegrationKey, String>,
    key: IntegrationKey,
    status: HttpStatusCode,
    freshness: Freshness = Freshness.CachedOrFetch,
): KtorFetchException {
    val failure = assertFailsWith<StoreException> { store.get(key, freshness) }
    val fetchError =
        failure.error as? StoreError.Fetch
            ?: fail("expected StoreError.Fetch, was ${failure.error}")
    val cause =
        fetchError.cause as? KtorFetchException
            ?: fail("expected KtorFetchException, was ${fetchError.cause}")
    assertEquals(status, cause.status)
    return cause
}

// Turbine's 3s default would nest inside the 25s shadow. Raising the Turbine deadline above
// the shadow makes runTest the only effective timeout.
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
