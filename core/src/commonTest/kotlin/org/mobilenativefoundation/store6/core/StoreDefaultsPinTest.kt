package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the two zero-config defaults the published Important Defaults page states but that no other
 * conformance test names as *the default*: the default [Freshness] and the absence of fetcher
 * retries. Both lines are public documentation, so both get a test that fails when the
 * documentation stops being true.
 */
@OptIn(ExperimentalStoreApi::class)
class StoreDefaultsPinTest {
    /**
     * The default [Freshness] is [Freshness.CachedOrFetch]. Two observations distinguish it from
     * the alternatives: an absent key fetches (so the default is not [Freshness.LocalOnly]), and a
     * resident fresh value is served without a second fetch (so it is not
     * [Freshness.MustBeFresh]).
     */
    @Test
    fun defaultFreshness_isCachedOrFetch_zeroConfig() =
        runTest(timeout = 60.seconds) {
            val fetcher = CountingFetcher()
            val store =
                store<TestKey, String> {
                    fetcher(fetcher::fetch)
                }

            try {
                val key = TestKey("default-freshness")

                assertEquals("v1:default-freshness", store.get(key))
                assertEquals(1, fetcher.count, "an absent key must fetch: the default is not LocalOnly")

                assertEquals("v1:default-freshness", store.get(key))
                assertEquals(
                    1,
                    fetcher.count,
                    "a resident fresh value must be served without a second fetch: " +
                        "the default is not MustBeFresh",
                )
            } finally {
                store.close()
            }
        }

    /**
     * The engine never retries your fetcher. One demand cycle invokes it exactly once, a failure
     * schedules no background retry and no backoff, and a later call is a new demand rather than a
     * continuation of the failed one.
     *
     * The quiet windows below run on [Dispatchers.Default] in real time on purpose: the engine's
     * own scope is `Dispatchers.Default` ([RealStore]), so it never observes `runTest`'s virtual
     * clock and a virtual-time advance would prove nothing. [NO_RETRY_WINDOW_MILLIS] is an order of
     * magnitude above the engine's internal fixed-delay scale, so a retry with backoff would have
     * fired inside it.
     */
    @Test
    fun fetcherFailure_isNotRetried_zeroConfig() =
        runTest(timeout = 60.seconds) {
            val fetcher = AlwaysFailingFetcher()
            val store =
                store<TestKey, String> {
                    fetcher(fetcher::fetch)
                }

            try {
                val key = TestKey("no-retry")

                // A terminalizing demand cycle: one call, one invocation.
                assertFailsWith<StoreException> { store.get(key, Freshness.MustBeFresh) }
                assertEquals(1, fetcher.count, "one demand cycle invokes the fetcher exactly once")

                withContext(Dispatchers.Default) { delay(NO_RETRY_WINDOW_MILLIS) }
                assertEquals(1, fetcher.count, "a failed fetch schedules no background retry")

                // A second call is new demand, not a continuation of the failed one.
                assertFailsWith<StoreException> { store.get(key, Freshness.MustBeFresh) }
                assertEquals(2, fetcher.count, "a second call is a new demand, not a retry")

                // The non-terminalizing path: a stream collector survives a fetch failure and stays
                // live. This is where a background retry could hide, so it gets its own window.
                val streamKey = TestKey("no-retry-stream")
                val collector =
                    launch(Dispatchers.Default) {
                        store.stream(streamKey).collect { }
                    }
                try {
                    withContext(Dispatchers.Default) {
                        while (fetcher.count < 3) {
                            delay(POLL_MILLIS)
                        }
                        delay(NO_RETRY_WINDOW_MILLIS)
                    }
                    assertEquals(
                        3,
                        fetcher.count,
                        "a live collector whose fetch failed triggers no background retry either",
                    )
                } finally {
                    collector.cancelAndJoin()
                }
            } finally {
                store.close()
            }
        }

    private class CountingFetcher {
        var count: Int = 0
            private set

        fun fetch(key: TestKey): String {
            count++
            return "v$count:${key.canonicalId()}"
        }
    }

    private class AlwaysFailingFetcher {
        var count: Int = 0
            private set

        fun fetch(key: TestKey): String {
            count++
            throw IllegalStateException("fetch failed for ${key.canonicalId()}")
        }
    }

    private companion object {
        /**
         * A real-time quiet window, an order of magnitude above the engine's internal fixed-delay
         * scale (`READER_RETRY_DELAY_MILLIS = 100L`), so any retry-with-backoff would fire inside it.
         */
        const val NO_RETRY_WINDOW_MILLIS = 1_000L
        const val POLL_MILLIS = 10L
    }
}
