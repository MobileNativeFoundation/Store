package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalStoreApi::class)
class StoreScopedMaintenanceRaceTest {
    @Test
    fun invalidateNamespace_watermarkThenLaterSuccessSuppressesResidentSignal() = runTest {
        val successEntered = CompletableDeferred<Unit>()
        val releaseSuccess = CompletableDeferred<Unit>()
        val durableBookkeeper = RecordingBookkeeper()
        var calls = 0
        val store = store<NamespacedTestKey, String> {
            fetcherOfResult { FetcherResult.Success("v${++calls}", etag = "e$calls") }
            bookkeeper(durableBookkeeper)
        }
        val key = NamespacedTestKey("a", "1")
        assertEquals("v1", store.get(key))
        durableBookkeeper.successEntered = successEntered
        durableBookkeeper.releaseSuccess = releaseSuccess

        val laterSuccess = backgroundScope.async { store.get(key, Freshness.MustBeFresh) }
        testScheduler.runCurrent()
        try {
            awaitFromDefault { successEntered.await() }
            val invalidation = backgroundScope.async {
                store.invalidateNamespace(StoreNamespace("a"))
            }
            testScheduler.runCurrent()
            assertTrue(
                durableBookkeeper.log.contains("advanceStaleWatermark:a"),
                "watermark must advance before resident status is rechecked",
            )
            releaseSuccess.complete(Unit)
            assertEquals("v2", awaitFromDefault { laterSuccess.await() })
            awaitFromDefault { invalidation.await() }
            val watermarkIndex = durableBookkeeper.log.indexOf("advanceStaleWatermark:a")
            val statusAfterWatermark =
                durableBookkeeper.log.indexOfLast { it == "status:a/1" }
            assertTrue(
                statusAfterWatermark > watermarkIndex,
                "resident signaling must reload status after the namespace watermark",
            )
            assertEquals("v2", store.get(key))
            assertEquals(2, calls, "later success must satisfy the earlier watermark")
        } finally {
            releaseSuccess.complete(Unit)
        }
        store.close()
    }

    @Test
    fun invalidateAll_globalWatermarkThenLaterSuccessSuppressesResidentSignal() = runTest {
        val successEntered = CompletableDeferred<Unit>()
        val releaseSuccess = CompletableDeferred<Unit>()
        val durableBookkeeper = RecordingBookkeeper()
        var calls = 0
        val store = store<TestKey, String> {
            fetcherOfResult { FetcherResult.Success("v${++calls}", etag = "e$calls") }
            bookkeeper(durableBookkeeper)
        }
        val key = TestKey("1")
        assertEquals("v1", store.get(key))
        durableBookkeeper.successEntered = successEntered
        durableBookkeeper.releaseSuccess = releaseSuccess

        val laterSuccess = backgroundScope.async { store.get(key, Freshness.MustBeFresh) }
        testScheduler.runCurrent()
        try {
            awaitFromDefault { successEntered.await() }
            val invalidation = backgroundScope.async { store.invalidateAll() }
            testScheduler.runCurrent()
            assertTrue(
                durableBookkeeper.log.contains("advanceGlobalStaleWatermark"),
                "global watermark must advance before resident status is rechecked",
            )
            releaseSuccess.complete(Unit)
            assertEquals("v2", awaitFromDefault { laterSuccess.await() })
            awaitFromDefault { invalidation.await() }
            val watermarkIndex = durableBookkeeper.log.indexOf("advanceGlobalStaleWatermark")
            val statusAfterWatermark = durableBookkeeper.log.indexOfLast { it == "status:test/1" }
            assertTrue(
                statusAfterWatermark > watermarkIndex,
                "resident signaling must reload status after the global watermark",
            )
            assertEquals("v2", store.get(key))
            assertEquals(2, calls)
        } finally {
            releaseSuccess.complete(Unit)
        }
        store.close()
    }

    @Test
    fun clearNamespace_fencesAffectedCommitTailsButAllowsUnrelatedCommit() = runTest {
        val backing = InMemorySourceOfTruth<NamespacedTestKey, String>()
        val gated = PostDeleteGateSourceOfTruth(backing)
        val affectedFetchStarted = CompletableDeferred<Unit>()
        val betweenSweepsFetchStarted = CompletableDeferred<Unit>()
        val releaseAffectedFetch = CompletableDeferred<Unit>()
        val counts = mutableMapOf<String, Int>()
        val store = store<NamespacedTestKey, String> {
            fetcher { key ->
                val identity = "${key.namespace.value}/${key.canonicalId()}"
                val call = counts.getOrElse(identity) { 0 } + 1
                counts[identity] = call
                if (identity == "a/1" && call == 2) {
                    affectedFetchStarted.complete(Unit)
                    releaseAffectedFetch.await()
                }
                if (identity == "a/2") betweenSweepsFetchStarted.complete(Unit)
                "$identity-v$call"
            }
            persistence(gated)
        }
        val affected = NamespacedTestKey("a", "1")
        val betweenSweeps = NamespacedTestKey("a", "2")
        val unrelated = NamespacedTestKey("b", "1")
        store.get(affected)
        store.get(unrelated)
        val oldTail = backgroundScope.async { runCatching { store.get(affected, Freshness.MustBeFresh) } }
        testScheduler.runCurrent()

        try {
            awaitFromDefault { affectedFetchStarted.await() }
            val clear = backgroundScope.async { store.clearNamespace(StoreNamespace("a")) }
            testScheduler.runCurrent()
            assertTrue(gated.namespaceDeleted.isCompleted, "clear must reach atomic bulk delete")
            releaseAffectedFetch.complete(Unit)
            val newEngineTail = backgroundScope.async { runCatching { store.get(betweenSweeps) } }
            testScheduler.runCurrent()
            awaitFromDefault { betweenSweepsFetchStarted.await() }
            assertEquals("b/1-v2", store.get(unrelated, Freshness.MustBeFresh))
            gated.releaseDelete.complete(Unit)
            awaitFromDefault { clear.await() }
            assertMissingResult(awaitFromDefault { oldTail.await() })
            assertMissingResult(awaitFromDefault { newEngineTail.await() })
            assertNull(backing.reader(affected).first())
            assertNull(backing.reader(betweenSweeps).first())
            assertEquals("b/1-v2", backing.reader(unrelated).first())
            assertLocalOnlyMissing(store, affected)
            assertLocalOnlyMissing(store, betweenSweeps)
        } finally {
            releaseAffectedFetch.complete(Unit)
            gated.releaseDelete.complete(Unit)
        }
        store.close()
    }

    @Test
    fun clearAll_fencesCommitTailsAndEngineCreatedBetweenSweeps() = runTest {
        val backing = InMemorySourceOfTruth<TestKey, String>()
        val gated = PostDeleteGateSourceOfTruth(backing)
        val affectedFetchStarted = CompletableDeferred<Unit>()
        val betweenSweepsFetchStarted = CompletableDeferred<Unit>()
        val releaseAffectedFetch = CompletableDeferred<Unit>()
        val counts = mutableMapOf<String, Int>()
        val store = store<TestKey, String> {
            fetcher { key ->
                val id = key.canonicalId()
                val call = counts.getOrElse(id) { 0 } + 1
                counts[id] = call
                if (id == "1" && call == 2) {
                    affectedFetchStarted.complete(Unit)
                    releaseAffectedFetch.await()
                }
                if (id == "2") betweenSweepsFetchStarted.complete(Unit)
                "$id-v$call"
            }
            persistence(gated)
        }
        val existing = TestKey("1")
        val betweenSweeps = TestKey("2")
        store.get(existing)
        val oldTail = backgroundScope.async { runCatching { store.get(existing, Freshness.MustBeFresh) } }
        testScheduler.runCurrent()

        try {
            awaitFromDefault { affectedFetchStarted.await() }
            val clear = backgroundScope.async { store.clearAll() }
            testScheduler.runCurrent()
            assertTrue(gated.allDeleted.isCompleted, "clearAll must reach atomic bulk delete")
            releaseAffectedFetch.complete(Unit)
            val newEngineTail = backgroundScope.async { runCatching { store.get(betweenSweeps) } }
            testScheduler.runCurrent()
            awaitFromDefault { betweenSweepsFetchStarted.await() }
            gated.releaseDelete.complete(Unit)
            awaitFromDefault { clear.await() }
            assertMissingResult(awaitFromDefault { oldTail.await() })
            assertMissingResult(awaitFromDefault { newEngineTail.await() })
            assertNull(backing.reader(existing).first())
            assertNull(backing.reader(betweenSweeps).first())
            assertLocalOnlyMissing(store, existing)
            assertLocalOnlyMissing(store, betweenSweeps)
        } finally {
            releaseAffectedFetch.complete(Unit)
            gated.releaseDelete.complete(Unit)
        }
        store.close()
    }

    private fun assertMissingResult(result: Result<String>) {
        val failure = assertIs<StoreException>(result.exceptionOrNull())
        assertIs<StoreError.Missing>(failure.error)
    }

    // Preserve the real-time Default-dispatch hop (never virtual time) and let the suite-level
    // runTest bound own cancellation.
    private suspend fun <T> awaitFromDefault(block: suspend () -> T): T =
        withContext(Dispatchers.Default) {
            block()
        }

    private suspend fun <K : StoreKey> assertLocalOnlyMissing(
        store: Store<K, String>,
        key: K,
    ) {
        val failure = assertFailsWith<StoreException> { store.get(key, Freshness.LocalOnly) }
        assertIs<StoreError.Missing>(failure.error)
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
