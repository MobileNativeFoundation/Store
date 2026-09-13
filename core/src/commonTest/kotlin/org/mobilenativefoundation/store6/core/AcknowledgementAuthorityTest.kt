package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.internal.DefaultFreshnessValidator
import org.mobilenativefoundation.store6.core.internal.EngineStoreMeta
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import org.mobilenativefoundation.store6.core.internal.KeyEngine
import org.mobilenativefoundation.store6.core.internal.KeyId
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.runtime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class, ExperimentalCoroutinesApi::class)
class AcknowledgementAuthorityTest {
    private val key = TestKey("ack")

    @Test
    fun evidenceCapturedAfterEarlierStaleness_allowsAtomicFreshAcknowledgement() = runTest {
        val bookkeeper = InMemoryBookkeeper()
        val store = store<TestKey, String> {
            fetcher { error("unexpected fetch") }
            bookkeeper(bookkeeper)
        }
        try {
            store.invalidate(key)
            val handle = assertNotNull(store.runtime()).writeHandle
            val evidence = assertNotNull(handle.captureFreshness(key))
            handle.applyAcknowledgement(key, "acknowledged", "ack-etag", evidence)
            assertEquals("acknowledged", store.get(key, Freshness.CachedOrFetch))
            assertFalse(assertNotNull(bookkeeper.status(key)).durablyStale)
            assertEquals("ack-etag", bookkeeper.status(key)?.meta?.etag)
        } finally {
            store.close()
        }
    }

    @Test
    fun laterKeyInvalidation_withoutCollectorsRemainsStale() = runTest {
        laterInvalidation { it.invalidate(key) }
    }

    @Test
    fun laterNamespaceInvalidation_withoutCollectorsRemainsStale() = runTest {
        laterInvalidation { it.invalidateNamespace(key.namespace) }
    }

    @Test
    fun laterGlobalInvalidation_withoutCollectorsRemainsStale() = runTest {
        laterInvalidation { it.invalidateAll() }
    }

    private suspend fun laterInvalidation(invalidate: suspend (Store<TestKey, String>) -> Unit) {
        val bookkeeper = InMemoryBookkeeper()
        val store = store<TestKey, String> {
            fetcher { error("unexpected fetch") }
            bookkeeper(bookkeeper)
        }
        try {
            val handle = assertNotNull(store.runtime()).writeHandle
            val evidence = handle.captureFreshness(key)
            invalidate(store)
            handle.applyAcknowledgement(key, "acknowledged", "ack-etag", evidence)
            assertEquals("acknowledged", store.get(key, Freshness.LocalOnly))
            assertTrue(assertNotNull(bookkeeper.status(key)).durablyStale)
            store.stream(key, Freshness.LocalOnly).test {
                assertTrue(assertIs<StoreResult.Data<String>>(awaitItem()).isStale)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun expiredEvidence_afterEvictionCannotReusePreviouslyFreshBookkeeping() = runTest {
        val bookkeeper = InMemoryBookkeeper()
        val store = store<TestKey, String> {
            fetcher { error("unexpected fetch") }
            bookkeeper(bookkeeper)
            maxIdleKeys(0)
        }
        try {
            bookkeeper.recordSuccess(key, EngineStoreMeta(0L, "old"))
            val handle = assertNotNull(store.runtime()).writeHandle
            val evidence = handle.captureFreshness(key)
            handle.applyAcknowledgement(key, "acknowledged", "ack-etag", evidence)
            assertTrue(assertNotNull(bookkeeper.status(key)).durablyStale)
            assertEquals("acknowledged", store.get(key, Freshness.LocalOnly))
        } finally {
            store.close()
        }
    }

    @Test
    fun evidenceFromClosedRuntime_cannotConfirmReopenedRuntime() = runTest {
        val bookkeeper = InMemoryBookkeeper()
        val sot = InMemorySourceOfTruth<TestKey, String>()
        fun create() = store<TestKey, String> {
            fetcher { error("unexpected fetch") }
            bookkeeper(bookkeeper)
            persistence(sot)
        }
        val original = create()
        val evidence = assertNotNull(original.runtime()).writeHandle.captureFreshness(key)
        original.close()
        bookkeeper.recordSuccess(key, EngineStoreMeta(0L, "old"))
        val reopened = create()
        try {
            assertNotNull(reopened.runtime()).writeHandle.applyAcknowledgement(
                key, "acknowledged", "ack-etag", evidence,
            )
            assertTrue(assertNotNull(bookkeeper.status(key)).durablyStale)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun evidenceForAliasSource_cannotConfirmDifferentTarget() = runTest {
        val bookkeeper = InMemoryBookkeeper()
        val store = store<TestKey, String> {
            fetcher { error("unexpected fetch") }
            bookkeeper(bookkeeper)
        }
        try {
            val handle = assertNotNull(store.runtime()).writeHandle
            val evidence = handle.captureFreshness(key)
            val target = TestKey("target")
            bookkeeper.recordSuccess(target, EngineStoreMeta(0L, "old"))
            handle.applyAcknowledgement(target, "acknowledged", "ack-etag", evidence)
            assertTrue(assertNotNull(bookkeeper.status(target)).durablyStale)
        } finally {
            store.close()
        }
    }

    @Test
    fun secondAcknowledgementWaitingAtWriteLock_keepsItsOwnMetadata() = runTest {
        val delegate = InMemorySourceOfTruth<TestKey, String>()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val sot = object : SourceOfTruth<TestKey, String> by delegate {
            override suspend fun write(key: TestKey, value: String) {
                if (value == "first") {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
                delegate.write(key, value)
            }
        }
        val bookkeeper = InMemoryBookkeeper()
        val engine = KeyEngine(
            key = key,
            keyId = KeyId.from(key),
            fetcher = object : Fetcher<TestKey, String> {
                override suspend fun fetch(key: TestKey, etag: String?): FetcherResult<String> =
                    error("unexpected fetch")
            },
            sot = sot,
            bookkeeper = bookkeeper,
            validator = DefaultFreshnessValidator,
            wallClock = FakeWallClock(now = 0L),
            engineScope = backgroundScope,
        )
        val firstEvidence = engine.captureFreshness()
        val secondEvidence = engine.captureFreshness()
        val first = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            engine.applyAcknowledgement("first", "first-etag", firstEvidence, adoption = null)
        }
        firstEntered.await()
        val second = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            engine.applyAcknowledgement("second", "second-etag", secondEvidence, adoption = null)
        }
        runCurrent()
        assertFalse(second.isCompleted)
        releaseFirst.complete(Unit)
        runCurrent()
        first.await()
        second.await()
        assertEquals("second", engine.get(Freshness.LocalOnly))
        assertEquals("second-etag", bookkeeper.status(key)?.meta?.etag)
    }

    @Test
    fun missingEvidence_staleMarkFailureAbortsBeforeSourceWrite() = runTest {
        val failure = IllegalStateException("stale mark unavailable")
        val durable = InMemoryBookkeeper()
        val bookkeeper = object : Bookkeeper by durable {
            override suspend fun markStale(key: StoreKey): Unit = throw failure
        }
        val sot = InMemorySourceOfTruth<TestKey, String>().also { it.write(key, "previous") }
        val store = store<TestKey, String> {
            fetcher { error("unexpected fetch") }
            persistence(sot)
            bookkeeper(bookkeeper)
        }
        try {
            val thrown = kotlin.test.assertFailsWith<StoreException> {
                assertNotNull(store.runtime()).writeHandle.applyAcknowledgement(
                    key, "acknowledged", "ack-etag", freshnessEvidence = null,
                )
            }
            kotlin.test.assertSame(failure, assertIs<StoreError.Persistence>(thrown.error).cause)
            assertEquals("previous", sot.reader(key).first())
        } finally {
            store.close()
        }
    }

    @Test
    fun missingEvidence_sourceWriteFailureRetainsCompletedDurableStaleMark() = runTest {
        val failure = IllegalStateException("source write unavailable")
        val bookkeeper = InMemoryBookkeeper()
        bookkeeper.recordSuccess(key, EngineStoreMeta(0L, "previous-etag"))
        val delegate = InMemorySourceOfTruth<TestKey, String>().also { it.write(key, "previous") }
        val sot = object : SourceOfTruth<TestKey, String> by delegate {
            override suspend fun write(key: TestKey, value: String): Unit = throw failure
        }
        val store = store<TestKey, String> {
            fetcher { error("unexpected fetch") }
            persistence(sot)
            bookkeeper(bookkeeper)
        }
        try {
            val thrown = kotlin.test.assertFailsWith<StoreException> {
                assertNotNull(store.runtime()).writeHandle.applyAcknowledgement(
                    key, "acknowledged", "ack-etag", freshnessEvidence = null,
                )
            }
            kotlin.test.assertSame(failure, assertIs<StoreError.Persistence>(thrown.error).cause)
            assertEquals("previous", sot.reader(key).first())
            assertTrue(assertNotNull(bookkeeper.status(key)).durablyStale)
        } finally {
            store.close()
        }
    }

    @Test
    fun oldFetchQueuedBehindAcknowledgement_keepsExactValueAndEtagPair() = runTest {
        val delegate = InMemorySourceOfTruth<TestKey, String>()
        val acknowledgementEntered = CompletableDeferred<Unit>()
        val releaseAcknowledgement = CompletableDeferred<Unit>()
        val sot = object : SourceOfTruth<TestKey, String> by delegate {
            override suspend fun write(key: TestKey, value: String) {
                if (value == "v2") {
                    acknowledgementEntered.complete(Unit)
                    releaseAcknowledgement.await()
                }
                delegate.write(key, value)
            }
        }
        val durable = InMemoryBookkeeper()
        val pairs = mutableListOf<Pair<String?, String?>>()
        val bookkeeper = object : Bookkeeper by durable {
            override suspend fun recordSuccess(key: StoreKey, meta: StoreMeta) {
                pairs += delegate.reader(this@AcknowledgementAuthorityTest.key).first() to meta.etag
                durable.recordSuccess(key, meta)
            }
        }
        val fetchEntered = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val conditionalEtags = mutableListOf<String?>()
        var fetches = 0
        val engine = KeyEngine(
            key = key,
            keyId = KeyId.from(key),
            fetcher = object : Fetcher<TestKey, String> {
                override suspend fun fetch(key: TestKey, etag: String?): FetcherResult<String> {
                    conditionalEtags += etag
                    return if (++fetches == 1) {
                        fetchEntered.complete(Unit)
                        releaseFetch.await()
                        FetcherResult.Success("v1", etag = "e1")
                    } else {
                        FetcherResult.NotModified(etag = etag)
                    }
                }
            },
            sot = sot,
            bookkeeper = bookkeeper,
            validator = DefaultFreshnessValidator,
            wallClock = FakeWallClock(now = 0L),
            engineScope = backgroundScope,
        )
        val evidence = engine.captureFreshness()
        val fetch = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            engine.get(Freshness.MustBeFresh)
        }
        runCurrent()
        fetchEntered.await()
        val acknowledgement = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            engine.applyAcknowledgement("v2", "e2", evidence, adoption = null)
        }
        acknowledgementEntered.await()
        releaseFetch.complete(Unit)
        runCurrent()
        assertFalse(fetch.isCompleted)
        releaseAcknowledgement.complete(Unit)
        runCurrent()
        acknowledgement.await()
        fetch.await()
        assertEquals(listOf<Pair<String?, String?>>("v2" to "e2", "v1" to "e1"), pairs)
        assertEquals("v1", engine.get(Freshness.LocalOnly))
        assertEquals("e1", bookkeeper.status(key)?.meta?.etag)
        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        assertEquals(listOf(null, "e1"), conditionalEtags)
    }
}
