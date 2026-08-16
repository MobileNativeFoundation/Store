package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.mobilenativefoundation.store6.core.internal.DefaultFreshnessValidator
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import org.mobilenativefoundation.store6.core.internal.RealStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalStoreApi::class)
class StoreZeroConfigEquivalenceTest {
    @Test
    fun zeroConfig_and_expertConfig_observeIdenticalDefaults() =
        runTest(timeout = 60.seconds) {
            val clock = FakeWallClock(now = 1_000_000L)
            val zeroConfigFetcher = ScriptedFetcher()
            val zeroConfig =
                store<TestKey, String> {
                    fetcher(zeroConfigFetcher::fetch)
                    wallClock(clock)
                } as RealStore<TestKey, String>

            val expertConfigFetcher = ScriptedFetcher()
            val expertConfig =
                store<TestKey, String> {
                    fetcher(expertConfigFetcher::fetch)
                    wallClock(clock)
                    persistence(InMemorySourceOfTruth())
                    bookkeeper(InMemoryBookkeeper())
                    freshnessValidator(DefaultFreshnessValidator)
                    maxIdleKeys(128)
                } as RealStore<TestKey, String>

            try {
                val zeroConfigTrace = scenario(zeroConfig, zeroConfigFetcher)
                assertEquals(EXPECTED_TRACE, zeroConfigTrace, "zero-config trace")
                assertEquals(4, zeroConfigFetcher.fetchCount, "zero-config total fetches")

                val expertConfigTrace = scenario(expertConfig, expertConfigFetcher)
                assertEquals(EXPECTED_TRACE, expertConfigTrace, "expert-config trace")
                assertEquals(4, expertConfigFetcher.fetchCount, "expert-config total fetches")

                assertEquals(zeroConfigTrace, expertConfigTrace)
            } finally {
                zeroConfigFetcher.releaseSecondFetch.complete(Unit)
                expertConfigFetcher.releaseSecondFetch.complete(Unit)
                zeroConfig.close()
                expertConfig.close()
                zeroConfig.awaitTerminationForTest()
                expertConfig.awaitTerminationForTest()
            }
        }

    @Test
    fun defaultMaxIdleKeys_matchesExplicit128Cap() =
        runTest(timeout = 60.seconds) {
            val zeroConfig =
                store<TestKey, String> {
                    fetcher { key -> "value:${key.canonicalId()}" }
                } as RealStore<TestKey, String>
            val expertConfig =
                store<TestKey, String> {
                    fetcher { key -> "value:${key.canonicalId()}" }
                    maxIdleKeys(128)
                } as RealStore<TestKey, String>

            try {
                assertIdleCap128(zeroConfig, "zero-config")
                assertIdleCap128(expertConfig, "expert-config")
            } finally {
                zeroConfig.close()
                expertConfig.close()
                zeroConfig.awaitTerminationForTest()
                expertConfig.awaitTerminationForTest()
            }
        }

    private suspend fun scenario(
        store: Store<TestKey, String>,
        scriptedFetcher: ScriptedFetcher,
    ): List<String> {
        val key = TestKey("ac6")
        val observations = mutableListOf<String>()

        observations += store.stream(key).take(2).toList().map(::label)
        observations += "warm=${store.get(key)}"
        store.invalidate(key)
        observations += "after-invalidate=${store.get(key)}"
        scriptedFetcher.secondFetchStarted.await()

        store.stream(key, Freshness.LocalOnly).test {
            val stale = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals(STALE_REFRESHING_LABEL, label(stale))
            observations += label(stale)

            scriptedFetcher.releaseSecondFetch.complete(Unit)

            var fresh = assertIs<StoreResult.Data<String>>(awaitItem())
            while (fresh.value == "v1:ac6") {
                assertEquals(STALE_REFRESHING_LABEL, label(fresh))
                fresh = assertIs(awaitItem())
            }
            assertEquals(FRESH_AFTER_INVALIDATE_LABEL, label(fresh))
            observations += label(fresh)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, scriptedFetcher.fetchCount, "post-invalidate refresh fetches")

        observations += "must-be-fresh=${store.get(key, Freshness.MustBeFresh)}"
        assertEquals(3, scriptedFetcher.fetchCount, "MustBeFresh fetches")

        val missing =
            assertFailsWith<StoreException> {
                store.get(TestKey("missing"), Freshness.LocalOnly)
            }
        val missingError = assertIs<StoreError.Missing>(missing.error)
        observations += "local-only=${missingError::class.simpleName}"
        assertEquals(3, scriptedFetcher.fetchCount, "LocalOnly must not fetch")

        store.clear(key)
        observations += "after-clear=${store.get(key)}"
        assertEquals(4, scriptedFetcher.fetchCount, "clear/get fetches")

        return observations
    }

    private suspend fun assertIdleCap128(
        store: RealStore<TestKey, String>,
        configLabel: String,
    ) {
        repeat(129) { index ->
            val key = TestKey("idle-$index")
            assertEquals("value:${key.canonicalId()}", store.get(key))
        }

        // Preserve the real-time Default-dispatch hop; the tests' explicit runTest bound owns
        // cancellation.
        withContext(Dispatchers.Default) {
            while (
                store.createdEngineCountForTest() != 129L ||
                store.createdEngineCountForTest() - store.destroyedEngineCountForTest() !=
                store.residentEngineCountForTest().toLong() ||
                store.idleEngineCountForTest() != store.residentEngineCountForTest()
            ) {
                yield()
            }
        }

        assertEquals(129L, store.createdEngineCountForTest(), "$configLabel created engines")
        assertEquals(128, store.residentEngineCountForTest(), "$configLabel resident engines")
        assertEquals(128, store.idleEngineCountForTest(), "$configLabel idle engines")
        assertEquals(1L, store.destroyedEngineCountForTest(), "$configLabel destroyed engines")
    }

    private fun label(result: StoreResult<String>): String =
        when (result) {
            is StoreResult.Data ->
                "Data(${result.value},${result.origin},${result.isStale},${result.refreshing})"
            is StoreResult.Loading -> "Loading"
            is StoreResult.Revalidated -> "Revalidated"
            is StoreResult.Error ->
                "Error(${result.error::class.simpleName},${result.servedStale})"
        }

    private class ScriptedFetcher {
        val secondFetchStarted = CompletableDeferred<Unit>()
        val releaseSecondFetch = CompletableDeferred<Unit>()
        var fetchCount: Int = 0
            private set

        suspend fun fetch(key: TestKey): String {
            val call = ++fetchCount
            if (call == 2) {
                secondFetchStarted.complete(Unit)
                releaseSecondFetch.await()
            }
            return "v$call:${key.canonicalId()}"
        }
    }

    private companion object {
        const val STALE_REFRESHING_LABEL = "Data(v1:ac6,MEMORY,true,true)"
        const val FRESH_AFTER_INVALIDATE_LABEL = "Data(v2:ac6,FETCHER,false,false)"

        val EXPECTED_TRACE =
            listOf(
                "Loading",
                "Data(v1:ac6,FETCHER,false,false)",
                "warm=v1:ac6",
                "after-invalidate=v1:ac6",
                STALE_REFRESHING_LABEL,
                FRESH_AFTER_INVALIDATE_LABEL,
                "must-be-fresh=v3:ac6",
                "local-only=Missing",
                "after-clear=v4:ac6",
            )
    }
}
