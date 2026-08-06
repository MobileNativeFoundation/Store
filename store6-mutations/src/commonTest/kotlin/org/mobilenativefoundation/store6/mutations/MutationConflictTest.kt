@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.StoreResults
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.core.seam.WallClock
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAckRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectDisposition
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationFailureRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationIntentRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyAliasRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyTombstoneRecord
import org.mobilenativefoundation.store6.testing.TestWallClock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class MutationConflictTest {
    @Test
    fun presentAbsentMatrix_survivesConflictRetryAndServerWins() = runTest {
        for (basePresent in listOf(true, false)) {
            for (retry in listOf(true, false)) {
                assertMatrixCell(
                    basePresent = basePresent,
                    retry = retry,
                    restartAfterReceipt = basePresent && retry,
                )
            }
        }
    }

    @Test
    fun retryPersistsGenerationPlusOneAndNewIdempotencyKeyBeforePush() = runTest {
        val delegate = InMemoryMutationJournalStorage()
        val storage = FailPointJournalStorage(delegate)
        val mutations = ConflictMutations()
        val backend = retainingConflictBackend()
        val key = MutationsTestKey("failed-g2-transaction")
        val policy = retryMinePolicy()
        val clock = TestWallClock(100L)
        var activeStore: MutationStore<MutationsTestKey, String>? =
            openConflictStore(storage, mutations, backend, policy, wallClock = clock)
        try {
            val initialStore = checkNotNull(activeStore)
            backend.seed(key, "base")
            initialStore.get(key, Freshness.MustBeFresh)
            val mutationId = initialStore.mutate(key, mutations.set, "mine")
            backend.pushBehavior = { _, value ->
                if (backend.receivedPushes.last().generation == 1) {
                    throw conflict(ConflictTestMeta(10L, "c1"))
                }
                MutationPresentAck(value, "acked-g2", null)
            }

            initialStore.drain(key)
            clock.advanceBy(2.seconds)
            val receipt = delegate.conflictState(mutationId)
            assertEquals(StoredPhase.REFRESH_REQUIRED, receipt.execution.phase)
            val generationOneKey = receipt.attempts.single().generationIdempotencyKey

            storage.armFailTransaction { before, after ->
                before.phase == StoredPhase.REFRESH_REQUIRED &&
                    after.phase == StoredPhase.READY &&
                    after.currentGeneration == before.currentGeneration + 1
            }
            assertFailsWith<FailPointTransactionException> { initialStore.drain(key) }

            val rolledBack = delegate.conflictState(mutationId)
            assertEquals(StoredPhase.REFRESH_REQUIRED, rolledBack.execution.phase)
            assertEquals(1, rolledBack.execution.currentGeneration)
            assertEquals(1, rolledBack.attempts.size)
            assertEquals(1, backend.receivedPushes.size)

            initialStore.close()
            activeStore = null
            val reopened = openConflictStore(storage, mutations, backend, policy, wallClock = clock)
            activeStore = reopened
            reopened.drain(key)
            val completed = delegate.conflictState(mutationId)
            assertEquals(StoredPhase.RETIRED, completed.execution.phase)
            assertEquals(listOf(1, 2), completed.attempts.map { it.generation })
            val generationTwo = completed.attempts.single { it.generation == 2 }
            assertNotEquals(generationOneKey, generationTwo.generationIdempotencyKey)
            assertEquals(generationTwo.generationIdempotencyKey, backend.receivedPushes.last().idempotencyKey)
            assertEquals(2, backend.receivedPushes.size)
        } finally {
            activeStore?.close()
        }
    }

    @Test
    fun freshnessBarrierRecapturesBaseThroughSameOrderedCaptureLoop() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ConflictMutations()
        val backend = retainingConflictBackend()
        val trace = CaptureTrace()
        val source = TracingSourceOfTruth(trace)
        val bookkeeper = TracingBookkeeper(trace)
        val clock = TestWallClock(100L)
        var theirs: MutationPresence<String>? = null
        val policy =
            ConflictPolicy(
                merge = { _, _, captured ->
                    theirs = captured
                    MutationConflictResolution.ServerWins
                },
            )
        val store =
            openConflictStore(
                storage,
                mutations,
                backend,
                policy,
                sourceOfTruth = source,
                bookkeeper = bookkeeper,
                wallClock = clock,
                fetcher = {
                    trace.events += "barrier-missing"
                    trace.afterBarrier = true
                    FetcherResult.Deleted
                },
            )
        val key = MutationsTestKey("missing-barrier")
        try {
            val mutationId = store.mutate(key, mutations.set, "mine")
            backend.pushBehavior = { _, _ -> throw conflict(null, "metadata-free conflict") }

            store.drain(key)
            clock.advanceBy(2.seconds)
            store.drain(key)

            assertSame(MutationPresence.Absent, theirs)
            assertEquals("barrier-missing", trace.events.first())
            val localReads = trace.events.indices.filter { trace.events[it] == "local" }
            assertEquals(1, localReads.size)
            val localRead = localReads.single()
            assertTrue(trace.events.subList(1, localRead).any { it == "status" })
            assertTrue(trace.events.subList(localRead + 1, trace.events.size).any { it == "status" })
            assertEquals("status", trace.events.last())
            assertEquals(StoredPhase.RETIRED, storage.conflictState(mutationId).execution.phase)
        } finally {
            store.close()
        }
    }

    @Test
    fun selectedMetadataAndExistenceFormCompletePrecondition() = runTest {
        val selected = MutableMeta(700L, "selected")
        val present = selectorCell(basePresent = true) { selected }
        selected.writtenAtEpochMillis = 9_999L
        selected.etag = "mutated-after-selection"
        assertEquals(MutationPresenceState.PRESENT, present.attempt.basePresence)
        assertContentEquals("base".encodeToByteArray(), present.attempt.baseBlob)
        assertEquals(700L, present.attempt.preconditionWrittenAt)
        assertEquals("selected", present.attempt.preconditionEtag)
        assertEquals(700L, assertNotNull(present.push.baseMeta).writtenAtEpochMillis)
        assertEquals("selected", present.push.baseMeta?.etag)

        val absent = selectorCell(basePresent = false) { null }
        assertEquals(MutationPresenceState.ABSENT, absent.attempt.basePresence)
        assertNull(absent.attempt.baseBlob)
        assertFalse(absent.attempt.preconditionMetaPresent)
        assertNull(absent.push.baseMeta)
        assertSame(MutationPresence.Absent, absent.push.base)

        val storage = InMemoryMutationJournalStorage()
        val mutations = ConflictMutations()
        val backend = FakeBackend()
        val store =
            openConflictStore(
                storage,
                mutations,
                backend,
                ConflictPolicy(precondition = { error("selector exploded") }),
            )
        val key = MutationsTestKey("selector-throw")
        try {
            val mutationId = store.mutate(key, mutations.set, "mine")
            store.drain(key)
            val parked = storage.conflictState(mutationId)
            assertEquals(StoredPhase.PARKED, parked.execution.phase)
            assertEquals(0, backend.receivedPushes.size)
            assertEquals(MutationFailureKind.CONFLICT, parked.activeFailure?.kind)
            assertEquals("selector-failed", parked.activeFailure?.detail)
        } finally {
            store.close()
        }
    }

    @Test
    fun preconditionSelectorRunsOncePerGenerationAndNeverOnTransportRetry() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ConflictMutations()
        val backend = retainingConflictBackend()
        val generations = mutableListOf<Int>()
        val selectedByGeneration = mutableMapOf<Int, MutableMeta>()
        val clock = TestWallClock(100L)
        val policy =
            ConflictPolicy(
                precondition = { candidate ->
                    generations += candidate.generation
                    MutableMeta(
                        writtenAtEpochMillis = 1_000L + candidate.generation,
                        etag = "selector-g${candidate.generation}",
                    ).also { selectedByGeneration[candidate.generation] = it }
                },
                merge = { _, mine, _ -> MutationConflictResolution.Retry(mine) },
            )
        val store = openConflictStore(storage, mutations, backend, policy, wallClock = clock)
        val key = MutationsTestKey("selector-count")
        backend.seed(key, "base")
        try {
            store.get(key, Freshness.MustBeFresh)
            val mutationId = store.mutate(key, mutations.set, "mine")
            val embeddedConflict = conflictException(ConflictTestMeta(1L, "embedded"), "embedded")
            val ordinaryTransport =
                StoreResults.exception(
                    StoreResults.fetchError("ordinary transport", embeddedConflict),
                    embeddedConflict,
                )
            backend.pushBehavior = { _, value ->
                when (backend.receivedPushes.size) {
                    1 -> throw ordinaryTransport
                    2 -> throw conflict(ConflictTestMeta(2L, "actual"))
                    else -> {
                        selectedByGeneration.getValue(2).apply {
                            writtenAtEpochMillis = 9_002L
                            etag = "mutated-g2"
                        }
                        MutationPresentAck(value, "acked", null)
                    }
                }
            }

            store.drain(key)
            clock.advanceBy(2.seconds)
            assertEquals(listOf(1), generations)
            val transport = storage.conflictState(mutationId)
            assertEquals(StoredPhase.READY, transport.execution.phase)
            assertEquals(MutationFailureKind.TRANSPORT, transport.failures.single().kind)
            assertEquals("push-failed", transport.failures.single().detail)
            val generationOne = transport.attempts.single()
            assertEquals(1_001L, generationOne.preconditionWrittenAt)
            assertEquals("selector-g1", generationOne.preconditionEtag)
            selectedByGeneration.getValue(1).apply {
                writtenAtEpochMillis = 9_001L
                etag = "mutated-g1"
            }

            store.drain(key)
            clock.advanceBy(3.seconds)
            assertEquals(listOf(1), generations)
            val conflicted = storage.conflictState(mutationId)
            assertEquals(StoredPhase.REFRESH_REQUIRED, conflicted.execution.phase)
            assertEquals(1_001L, conflicted.attempts.single().preconditionWrittenAt)
            assertEquals("selector-g1", conflicted.attempts.single().preconditionEtag)
            assertEquals(1_001L, assertNotNull(backend.receivedPushes[1].baseMeta).writtenAtEpochMillis)
            assertEquals("selector-g1", backend.receivedPushes[1].baseMeta?.etag)

            store.drain(key)
            assertEquals(listOf(1, 2), generations)
            val completed = storage.conflictState(mutationId)
            assertEquals(StoredPhase.RETIRED, completed.execution.phase)
            val frozenOne = completed.attempts.single { it.generation == 1 }
            val frozenTwo = completed.attempts.single { it.generation == 2 }
            assertNotSame(selectedByGeneration.getValue(1), selectedByGeneration.getValue(2))
            assertEquals(1_001L, frozenOne.preconditionWrittenAt)
            assertEquals("selector-g1", frozenOne.preconditionEtag)
            assertEquals(1_002L, frozenTwo.preconditionWrittenAt)
            assertEquals("selector-g2", frozenTwo.preconditionEtag)
            val generationTwoPush = backend.receivedPushes.single { it.generation == 2 }
            assertEquals(1_002L, assertNotNull(generationTwoPush.baseMeta).writtenAtEpochMillis)
            assertEquals("selector-g2", generationTwoPush.baseMeta?.etag)
        } finally {
            store.close()
        }
    }

    @Test
    fun mustBeFreshIsBarrierThenTheirsIsRecaptured() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ConflictMutations()
        val backend = retainingConflictBackend()
        val key = MutationsTestKey("discard-barrier-value")
        val trace = mutableListOf<String>()
        val clock = TestWallClock(100L)
        val barrierEntered = CompletableDeferred<Unit>()
        val releaseBarrier = CompletableDeferred<Unit>()
        val recaptureEntered = CompletableDeferred<Unit>()
        val recapturedY = CompletableDeferred<String>()
        var recaptureArmed = false
        var mergeTheirs: MutationPresence<String>? = null
        val policy =
            ConflictPolicy(
                merge = { _, _, theirs ->
                    mergeTheirs = theirs
                    trace += "merge:${theirs.valueOrAbsent()}"
                    MutationConflictResolution.ServerWins
                },
            )
        val engine =
            openConflictEngine(
                storage,
                mutations,
                backend,
                policy,
                wallClock = clock,
                baseReader = {
                    if (!recaptureArmed) {
                        "base"
                    } else {
                        recaptureEntered.complete(Unit)
                        recapturedY.await()
                    }
                },
                freshnessBarrier = {
                    trace += "barrier:X"
                    trace += "commit:X"
                    barrierEntered.complete(Unit)
                    releaseBarrier.await()
                    recaptureArmed = true
                },
            )
        try {
            val mutationId = engine.mutate(key, mutations.set, "mine")
            backend.pushBehavior = { _, _ -> throw conflict(ConflictTestMeta(2L, "conflict")) }
            engine.drain(key)
            assertEquals(StoredPhase.REFRESH_REQUIRED, storage.conflictState(mutationId).execution.phase)
            clock.advanceBy(2.seconds)

            val resolving = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { engine.drain(key) }
            select<Unit> {
                barrierEntered.onAwait { }
                resolving.onJoin {
                    fail("Conflict resolution completed before the MustBeFresh commit gate.")
                }
            }
            releaseBarrier.complete(Unit)
            select<Unit> {
                recaptureEntered.onAwait { }
                resolving.onJoin {
                    fail("Conflict resolution completed before post-close raw Y was mapped.")
                }
            }
            trace += "external:Y"
            trace += "raw:Y"
            recapturedY.complete("recaptured-Y")
            resolving.await()

            assertEquals("recaptured-Y", assertIs<MutationPresence.Present<String>>(mergeTheirs).value)
            assertEquals(
                listOf(
                    "barrier:X",
                    "commit:X",
                    "external:Y",
                    "raw:Y",
                    "merge:recaptured-Y",
                ),
                trace,
            )
            assertEquals(StoredPhase.RETIRED, storage.conflictState(mutationId).execution.phase)
            assertEquals(1, backend.receivedPushes.size)
        } finally {
            releaseBarrier.complete(Unit)
            recapturedY.complete("recaptured-Y")
        }
    }

    @Test
    fun retryPersistsGenerationPlusOneBeforeTransmission() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ConflictMutations()
        val backend = FakeBackend()
        val clock = TestWallClock(100L)
        val store =
            openConflictStore(storage, mutations, backend, retryMinePolicy(), wallClock = clock)
        val key = MutationsTestKey("persist-before-send")
        backend.seed(key, "base")
        var atTransmission: ConflictState? = null
        try {
            store.get(key, Freshness.MustBeFresh)
            val mutationId = store.mutate(key, mutations.set, "mine")
            backend.pushBehavior = { _, value ->
                val push = backend.receivedPushes.last()
                if (push.generation == 1) throw conflict(ConflictTestMeta(3L, "c1"))
                atTransmission = storage.conflictState(mutationId)
                MutationPresentAck(value, "acked", null)
            }

            store.drain(key)
            clock.advanceBy(2.seconds)
            store.drain(key)

            val observed = assertNotNull(atTransmission)
            val generationOne = observed.attempts.single { it.generation == 1 }
            val generationTwo = observed.attempts.single { it.generation == 2 }
            assertEquals(StoredPhase.INFLIGHT, observed.execution.phase)
            assertEquals(2, observed.execution.currentGeneration)
            assertNotEquals(generationOne.generationIdempotencyKey, generationTwo.generationIdempotencyKey)
            val sent = backend.receivedPushes.last()
            assertEquals(generationTwo.generationIdempotencyKey, sent.idempotencyKey)
            assertEquals(generationTwo.basePresence, sent.base.toState())
            assertEquals(generationTwo.minePresence, sent.mine.toState())
            assertContentEquals(generationTwo.baseBlob, sent.base.encode(ConflictStringCodec))
            assertContentEquals(generationTwo.mineBlob, sent.mine.encode(ConflictStringCodec))
        } finally {
            store.close()
        }
    }

    @Test
    fun mergeThrow_parksAsNormalizedConflictWithoutTransport() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ConflictMutations()
        val backend = FakeBackend()
        val clock = TestWallClock(100L)
        val store =
            openConflictStore(
                storage,
                mutations,
                backend,
                ConflictPolicy(merge = { _, _, _ -> error("merge exploded") }),
                wallClock = clock,
            )
        val key = MutationsTestKey("merge-throw")
        try {
            val mutationId = store.mutate(key, mutations.set, "mine")
            backend.pushBehavior = { _, _ -> throw conflict(ConflictTestMeta(4L, "c1")) }
            store.drain(key)
            clock.advanceBy(2.seconds)
            store.drain(key)

            val state = storage.conflictState(mutationId)
            assertEquals(StoredPhase.PARKED, state.execution.phase)
            assertEquals(1, state.attempts.size)
            assertEquals(1, backend.receivedPushes.size)
            assertEquals(MutationFailureKind.CONFLICT, state.activeFailure?.kind)
            assertEquals("merge-failed", state.activeFailure?.detail)
        } finally {
            store.close()
        }
    }

    @Test
    fun mergeCancellation_rethrowsAndPreservesRefreshRequiredGeneration() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ConflictMutations()
        val backend = retainingConflictBackend()
        val cancellation = CancellationException("cancel exact merge")
        var cancelMerge = true
        val clock = TestWallClock(100L)
        val policy =
            ConflictPolicy(
                merge = { _, mine, _ ->
                    if (cancelMerge) throw cancellation
                    MutationConflictResolution.Retry(mine)
                },
            )
        val store = openConflictStore(storage, mutations, backend, policy, wallClock = clock)
        val key = MutationsTestKey("merge-cancellation")
        try {
            val mutationId = store.mutate(key, mutations.set, "mine")
            backend.pushBehavior = { _, value ->
                if (backend.receivedPushes.last().generation == 1) {
                    throw conflict(ConflictTestMeta(5L, "c1"))
                }
                MutationPresentAck(value, "acked", null)
            }
            store.drain(key)
            clock.advanceBy(2.seconds)
            val before = storage.conflictState(mutationId).fingerprint()

            val thrown = assertFailsWith<CancellationException> { store.drain(key) }
            assertSame(cancellation, thrown)
            assertEquals(before, storage.conflictState(mutationId).fingerprint())
            assertEquals(1, backend.receivedPushes.size)

            cancelMerge = false
            store.drain(key)
            assertEquals(StoredPhase.RETIRED, storage.conflictState(mutationId).execution.phase)
            assertEquals(2, backend.receivedPushes.size)
        } finally {
            store.close()
        }
    }

    @Test
    fun globalDrainContinuesAfterMergeFailure() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ConflictMutations()
        val backend = FakeBackend()
        val clock = TestWallClock(10_000L)
        val policy =
            ConflictPolicy(
                merge = { _, mine, _ ->
                    val value = assertIs<MutationPresence.Present<String>>(mine).value
                    if (value == "mine-A") error("A merge failed")
                    MutationConflictResolution.Retry(mine)
                },
            )
        val resolver =
            MutationKeyResolver<MutationsTestKey> { identity ->
                MutationsTestKey(identity.canonicalId, StoreNamespace(identity.namespace))
            }
        val store =
            openConflictStore(
                storage,
                mutations,
                backend,
                policy,
                wallClock = clock,
                keyResolver = resolver,
            )
        val keyA = MutationsTestKey("global-A")
        val keyB = MutationsTestKey("global-B", StoreNamespace("global-healthy"))
        try {
            val mutationA = store.mutate(keyA, mutations.set, "mine-A")
            val mutationB = store.mutate(keyB, mutations.set, "mine-B")
            backend.pushBehavior = { _, value ->
                if (value == "mine-A") throw conflict(ConflictTestMeta(6L, "a"))
                MutationPresentAck(value, "acked-b", null)
            }
            store.drain(keyA)
            clock.advanceBy(2.seconds)

            store.drain()

            val stateA = storage.conflictState(mutationA)
            val stateB = storage.conflictState(mutationB)
            assertEquals(StoredPhase.PARKED, stateA.execution.phase)
            assertEquals("merge-failed", stateA.activeFailure?.detail)
            assertEquals(StoredPhase.RETIRED, stateB.execution.phase)
            assertTrue(backend.receivedPushes.any { it.key.canonicalId() == keyB.canonicalId() })
        } finally {
            store.close()
        }
    }

    @Test
    fun serverWinsSkipsEffectsAndRetiresWithoutAnotherPush() = runTest {
        val delegate = InMemoryMutationJournalStorage()
        val storage = FailPointJournalStorage(delegate)
        val mutations = ConflictMutations(withEffect = true)
        val backend = retainingConflictBackend()
        val handle = CountingConflictHandle()
        val clock = TestWallClock(100L)
        val engine =
            openConflictEngine(
                storage,
                mutations,
                backend,
                policy = null,
                handle = handle,
                wallClock = clock,
            )
        val key = MutationsTestKey("server-wins-default")
        val observedRevisions = mutableListOf<String>()
        val collector =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                engine.changes.collect { changed ->
                    if (changed.canonicalId() == key.canonicalId()) {
                        observedRevisions += changed.canonicalId()
                    }
                }
            }
        try {
            val mutationId = engine.mutate(key, mutations.set, "mine")
            backend.pushBehavior = { _, _ -> throw conflict(ConflictTestMeta(7L, "server")) }
            engine.drain(key)
            clock.advanceBy(2.seconds)
            testScheduler.runCurrent()
            val revisionCountBeforeTerminal = observedRevisions.size
            assertEquals(StoredPhase.REFRESH_REQUIRED, delegate.conflictState(mutationId).execution.phase)
            assertEquals("mine", engine.overlay.apply(key, "base"))

            storage.armFailTransaction { before, after ->
                before.phase == StoredPhase.REFRESH_REQUIRED && after.phase == StoredPhase.RETIRED
            }
            assertFailsWith<FailPointTransactionException> { engine.drain(key) }
            testScheduler.runCurrent()

            val rolledBack = delegate.conflictState(mutationId)
            assertEquals(StoredPhase.REFRESH_REQUIRED, rolledBack.execution.phase)
            assertTrue(rolledBack.effects.all { it.disposition == MutationEffectDisposition.PENDING })
            assertEquals(0L, assertNotNull(rolledBack.client).retiredThroughSequence)
            assertEquals(revisionCountBeforeTerminal, observedRevisions.size)
            assertEquals("mine", engine.overlay.apply(key, "base"))
            assertEquals(1, backend.receivedPushes.size)

            engine.drain(key)

            val state = delegate.conflictState(mutationId)
            assertEquals(StoredPhase.RETIRED, state.execution.phase)
            assertTrue(state.effects.isNotEmpty())
            assertTrue(state.effects.all { it.disposition == MutationEffectDisposition.SKIPPED })
            assertEquals(1L, assertNotNull(state.client).retiredThroughSequence)
            assertEquals(1, backend.receivedPushes.size)
            assertEquals(emptyList(), state.acks)
            assertEquals(emptyList(), state.aliases)
            assertEquals(emptyList(), state.tombstones)
            assertEquals(0, handle.markStaleCalls)
            assertEquals(0, handle.applyCalls)
            assertEquals(0, handle.confirmFreshCalls)
            assertEquals("base", engine.overlay.apply(key, "base"))
            testScheduler.runCurrent()
            assertTrue(observedRevisions.size > revisionCountBeforeTerminal)
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun unchangedRepeatedConflictEventuallyParks() = runTest {
        val unchangedMetas =
            listOf(
                ConflictTestMeta(8L, "A"),
                ConflictTestMeta(8L, "A"),
                ConflictTestMeta(8L, "A"),
            )
        val unchangedRun = runConflictSequence(unchangedMetas)
        val unchanged = unchangedRun.state
        assertEquals(StoredPhase.PARKED, unchanged.execution.phase)
        assertEquals(3, unchanged.execution.currentGeneration)
        assertEquals(1, unchanged.execution.attempt)
        assertEquals(3, unchanged.attempts.size)
        assertConflictReceipts(unchanged, unchangedMetas, unchangedRun.receiptTimes)
        assertEquals(1, unchanged.failures.size)
        assertEquals(MutationFailureKind.CONFLICT, unchanged.activeFailure?.kind)
        assertEquals("conflict-unchanged-bound", unchanged.activeFailure?.detail)

        val resetMetas =
            listOf(
                ConflictTestMeta(8L, "A"),
                ConflictTestMeta(8L, "A"),
                ConflictTestMeta(8L, "B"),
            )
        val resetRun = runConflictSequence(resetMetas)
        val reset = resetRun.state
        assertEquals(StoredPhase.REFRESH_REQUIRED, reset.execution.phase)
        assertEquals(3, reset.execution.currentGeneration)
        assertEquals(1, reset.execution.attempt)
        assertEquals(3, reset.attempts.size)
        assertConflictReceipts(reset, resetMetas, resetRun.receiptTimes)
        assertEquals(emptyList(), reset.failures)
        // The bound is derived solely from the three immutable attempt rows; no counter record is
        // addressable through the frozen nine-record journal interface.
    }

    @Test
    fun refreshFailurePreservesCurrentGenerationAndBackoffFacts() = runTest {
        val codecRestart =
            runRefreshRequiredRestartCase(
                name = "refresh-codec",
                valueCodec = ThrowingVersionOneConflictCodec,
                drain = { restarted, key -> restarted.drain(key) },
            )
        val identityRestart =
            runRefreshRequiredRestartCase(
                name = "refresh-identity",
                keyResolver = MutationKeyResolver { null },
                drain = { restarted, _ -> restarted.drain() },
            )
        assertRefreshRequiredRestartCases(codecRestart, identityRestart)

        val storage = InMemoryMutationJournalStorage()
        val mutations = ConflictMutations()
        val backend = retainingConflictBackend()
        var refreshFailure: Throwable? = null
        val clock = TestWallClock(100L)
        val store =
            openConflictStore(
                storage,
                mutations,
                backend,
                retryMinePolicy(),
                wallClock = clock,
                fetcher = { key ->
                    refreshFailure?.let { throw it }
                    backend.loadResult(key)
                },
            )
        val key = MutationsTestKey("refresh-failure")
        backend.seed(key, "base")
        try {
            store.get(key, Freshness.MustBeFresh)
            val mutationId = store.mutate(key, mutations.set, "mine")
            backend.pushBehavior = { _, value ->
                if (backend.receivedPushes.last().generation == 1) {
                    throw conflict(ConflictTestMeta(10L, "c1"))
                }
                MutationPresentAck(value, "acked", null)
            }
            store.drain(key)
            clock.advanceBy(2.seconds)
            val before = storage.conflictState(mutationId).fullFingerprint(backend.receivedPushes.size)

            refreshFailure = IllegalStateException("refresh unavailable")
            assertFailsWith<StoreException> { store.drain(key) }
            assertEquals(before, storage.conflictState(mutationId).fullFingerprint(backend.receivedPushes.size))

            refreshFailure = null
            store.drain(key)
            assertEquals(StoredPhase.RETIRED, storage.conflictState(mutationId).execution.phase)
        } finally {
            store.close()
        }
    }

    @Test
    fun serverWinsCancellationAfterCommit_stillPublishesOverlayRevision() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = ConflictMutations()
        val backend = FakeBackend()
        val policy = ConflictPolicy(merge = { _, _, _ -> MutationConflictResolution.ServerWins })
        val clock = TestWallClock(100L)
        val engine = openConflictEngine(storage, mutations, backend, policy, wallClock = clock)
        val key = MutationsTestKey("cancelled-server-wins")
        val blocker = MutationsTestKey("server-wins-signal-blocker")
        val filler = MutationsTestKey("server-wins-signal-filler")
        val mutationId = engine.mutate(key, mutations.set, "mine")
        backend.pushBehavior = { _, _ -> throw conflict(ConflictTestMeta(11L, "server")) }
        engine.drain(key)
        clock.advanceBy(2.seconds)
        assertEquals(StoredPhase.REFRESH_REQUIRED, storage.conflictState(mutationId).execution.phase)
        assertEquals("mine", engine.overlay.apply(key, "base"))

        val initialKeyObserved = CompletableDeferred<Unit>()
        val blockerEntered = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        val retiredObserved = CompletableDeferred<Unit>()
        val collector =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                engine.changes.collect { changed ->
                    when (changed.canonicalId()) {
                        key.canonicalId() -> {
                            if (!initialKeyObserved.complete(Unit)) retiredObserved.complete(Unit)
                        }
                        blocker.canonicalId() -> {
                            blockerEntered.complete(Unit)
                            releaseBlocker.await()
                        }
                    }
                }
            }
        try {
            testScheduler.runCurrent()
            initialKeyObserved.await()
            engine.mutate(blocker, mutations.set, "blocker")
            testScheduler.runCurrent()
            blockerEntered.await()
            // The blocked collector has consumed [blocker]. This second revision fills the sole
            // replay slot, so the following ServerWins key revision must suspend at publication.
            engine.mutate(filler, mutations.set, "filler")
            val draining =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    engine.drain(key)
                }
            assertFalse(draining.isCompleted)
            assertEquals(StoredPhase.RETIRED, storage.conflictState(mutationId).execution.phase)
            assertEquals("base", engine.overlay.apply(key, "base"))
            assertFalse(retiredObserved.isCompleted)

            draining.cancel()
            assertFalse(retiredObserved.isCompleted)
            releaseBlocker.complete(Unit)
            testScheduler.runCurrent()
            draining.join()
            assertTrue(draining.isCancelled)
            assertTrue(retiredObserved.isCompleted)
            assertEquals("base", engine.overlay.apply(key, "base"))
        } finally {
            releaseBlocker.complete(Unit)
            collector.cancelAndJoin()
        }
    }
}

private const val CONFLICT_CLIENT_ID: String = "client-0"

private class ConflictMutations(
    withEffect: Boolean = false,
) {
    lateinit var set: MutatorRef<MutationsTestKey, String, String>

    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            set =
                upsert(
                    id = "conflict-set",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = { key, _ ->
                        if (withEffect) {
                            StaleSet(keys = setOf(key), namespaces = emptySet())
                        } else {
                            StaleSet(keys = emptySet(), namespaces = emptySet())
                        }
                    },
                ) { _, value -> MutationPresence.Present(value) }
        }
}

private object ConflictStringCodec : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(version: Int, bytes: ByteArray): String {
        require(version == 1)
        return bytes.decodeToString()
    }
}

private object ThrowingVersionOneConflictCodec : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(version: Int, bytes: ByteArray): String {
        require(version == 1)
        error("version-1 value decode failed")
    }
}

private data class ConflictPolicy(
    val precondition: ((MutationPreconditionCandidate<MutationsTestKey, String>) -> StoreMeta?)? = null,
    val merge:
        ((MutationPresence<String>, MutationPresence<String>, MutationPresence<String>) ->
            MutationConflictResolution<String>)? = null,
)

private fun retryMinePolicy(): ConflictPolicy =
    ConflictPolicy(merge = { _, mine, _ -> MutationConflictResolution.Retry(mine) })

private fun retainingConflictBackend(): FakeBackend =
    FakeBackend().apply {
        retireBehavior = {
            MutationRetirementAck(confirmedThroughSequence = 0L)
        }
    }

private fun openConflictStore(
    storage: MutationJournalStorage,
    mutations: ConflictMutations,
    backend: FakeBackend,
    policy: ConflictPolicy?,
    sourceOfTruth: SourceOfTruth<MutationsTestKey, String> = MutationSourceOfTruth(),
    bookkeeper: Bookkeeper = MutationBookkeeper(),
    wallClock: WallClock = TestWallClock(100L),
    fetcher: suspend (MutationsTestKey) -> FetcherResult<String> = backend::loadResult,
    keyResolver: MutationKeyResolver<MutationsTestKey> = MutationsTestKeyResolver,
    valueCodec: MutationCodec<String> = ConflictStringCodec,
): MutationStore<MutationsTestKey, String> =
    mutationStore(
        registry = mutations.registry,
        server = backend,
        keyResolver = keyResolver,
        valueCodecVersion = 1,
        valueCodec = valueCodec,
    ) {
        fetcherOfResult(fetcher)
        persistence(sourceOfTruth)
        bookkeeper(bookkeeper)
        wallClock(wallClock)
        journalStorage(storage)
        if (policy != null) {
            conflicts {
                policy.precondition?.let { precondition(it) }
                policy.merge?.let { merge(it) }
            }
        }
    }

private fun openConflictEngine(
    storage: MutationJournalStorage,
    mutations: ConflictMutations,
    backend: FakeBackend,
    policy: ConflictPolicy?,
    handle: StoreWriteHandle<MutationsTestKey, String> = ConflictNoopHandle,
    baseReader: suspend (MutationsTestKey) -> String? = { "base" },
    freshnessBarrier: suspend (MutationsTestKey) -> Unit = {},
    wallClock: WallClock = TestWallClock(100L),
): MutationEngine<MutationsTestKey, String> {
    val journal =
        StorageBackedMutationJournal<String>(
            storage = storage,
            registrations = mutations.registry.registrations,
            clientId = CONFLICT_CLIENT_ID,
            hydrateOnFirstUse = true,
        )
    return MutationEngine(
        registry = mutations.registry,
        server = backend,
        journal = journal,
        keyResolver = MutationsTestKeyResolver,
        valueCodecVersion = 1,
        valueCodec = ConflictStringCodec,
        conflicts = policy?.let { MutationConflictRegistration(it.precondition, it.merge) },
        baseReader = baseReader,
        freshnessBarrier = freshnessBarrier,
        wallClock = wallClock,
        clientId = CONFLICT_CLIENT_ID,
    ).also { it.bind(handle) }
}

private object ConflictNoopHandle : StoreWriteHandle<MutationsTestKey, String> {
    override suspend fun apply(key: MutationsTestKey, value: String) = Unit

    override suspend fun markStale(key: MutationsTestKey) = Unit

    override suspend fun confirmFresh(key: MutationsTestKey, etag: String?) = Unit
}

private class CountingConflictHandle : StoreWriteHandle<MutationsTestKey, String> {
    var applyCalls: Int = 0
        private set
    var markStaleCalls: Int = 0
        private set
    var confirmFreshCalls: Int = 0
        private set

    override suspend fun apply(key: MutationsTestKey, value: String) {
        applyCalls += 1
    }

    override suspend fun markStale(key: MutationsTestKey) {
        markStaleCalls += 1
    }

    override suspend fun confirmFresh(key: MutationsTestKey, etag: String?) {
        confirmFreshCalls += 1
    }
}

private suspend fun TestScope.assertMatrixCell(
    basePresent: Boolean,
    retry: Boolean,
    restartAfterReceipt: Boolean,
) {
    val storage = InMemoryMutationJournalStorage()
    val mutations = ConflictMutations()
    val backend = retainingConflictBackend()
    val key = MutationsTestKey("matrix-${if (basePresent) "present" else "absent"}-${if (retry) "retry" else "server"}")
    val sourceOfTruth = MutationSourceOfTruth<MutationsTestKey, String>()
    val bookkeeper = MutationBookkeeper()
    val clock = TestWallClock(100L)
    var mergeBase: PresenceSnapshot? = null
    var mergeMine: PresenceSnapshot? = null
    val policy =
        ConflictPolicy(
            merge = { base, mine, _ ->
                mergeBase = base.snapshot()
                mergeMine = mine.snapshot()
                if (retry) {
                    MutationConflictResolution.Retry(MutationPresence.Present("merged"))
                } else {
                    MutationConflictResolution.ServerWins
                }
            },
        )
    var activeStore: MutationStore<MutationsTestKey, String>? =
        openConflictStore(
            storage,
            mutations,
            backend,
            policy,
            sourceOfTruth = sourceOfTruth,
            bookkeeper = bookkeeper,
            wallClock = clock,
        )
    try {
        var currentStore = checkNotNull(activeStore)
        if (basePresent) {
            backend.seed(key, "base")
            currentStore.get(key, Freshness.MustBeFresh)
        }
        val mutationId = currentStore.mutate(key, mutations.set, "mine")
        backend.pushBehavior = { _, value ->
            if (backend.receivedPushes.last().generation == 1) {
                throw conflict(ConflictTestMeta(20L, "matrix"))
            }
            MutationPresentAck(value, "acked", null)
        }
        currentStore.drain(key)
        clock.advanceBy(2.seconds)
        val received = storage.conflictState(mutationId)
        assertEquals(StoredPhase.REFRESH_REQUIRED, received.execution.phase)
        val durablePreImageBase = received.attempts.single().baseSnapshot()
        val durablePreImageMine = received.attempts.single().mineSnapshot()
        if (restartAfterReceipt) {
            currentStore.close()
            activeStore = null
            sourceOfTruth.write(key, "perturbed-local")
            bookkeeper.recordSuccess(key, ConflictTestMeta(99L, "perturbed-local"))
            backend.seed(key, "perturbed-server")
            currentStore =
                openConflictStore(
                    storage,
                    mutations,
                    backend,
                    policy,
                    sourceOfTruth = sourceOfTruth,
                    bookkeeper = bookkeeper,
                    wallClock = clock,
                )
            activeStore = currentStore
        }
        currentStore.drain(key)
        val terminal = storage.conflictState(mutationId)
        assertEquals(StoredPhase.RETIRED, terminal.execution.phase)
        assertEquals(if (retry) 2 else 1, terminal.attempts.size)
        assertEquals(if (retry) 2 else 1, backend.receivedPushes.size)
        assertEquals(
            if (basePresent) MutationPresenceState.PRESENT else MutationPresenceState.ABSENT,
            terminal.attempts.first().basePresence,
        )
        if (restartAfterReceipt) {
            assertEquals(durablePreImageBase, mergeBase)
            assertEquals(durablePreImageMine, mergeMine)
            assertEquals(PresenceSnapshot(MutationPresenceState.PRESENT, "base"), mergeBase)
            assertEquals(PresenceSnapshot(MutationPresenceState.PRESENT, "mine"), mergeMine)
        }
    } finally {
        activeStore?.close()
    }
}

private data class PresenceSnapshot(
    val state: MutationPresenceState,
    val value: String?,
)

private fun MutationPresence<String>.snapshot(): PresenceSnapshot =
    when (this) {
        is MutationPresence.Present -> PresenceSnapshot(MutationPresenceState.PRESENT, value)
        MutationPresence.Absent -> PresenceSnapshot(MutationPresenceState.ABSENT, null)
    }

private fun MutationAttemptRecord.baseSnapshot(): PresenceSnapshot =
    PresenceSnapshot(basePresence, baseBlob?.decodeToString())

private fun MutationAttemptRecord.mineSnapshot(): PresenceSnapshot =
    PresenceSnapshot(minePresence, mineBlob?.decodeToString())

private data class SelectorCell(
    val attempt: MutationAttemptRecord,
    val push: MutationPush<MutationsTestKey, String>,
)

private suspend fun selectorCell(
    basePresent: Boolean,
    selector: (MutationPreconditionCandidate<MutationsTestKey, String>) -> StoreMeta?,
): SelectorCell {
    val storage = InMemoryMutationJournalStorage()
    val mutations = ConflictMutations()
    val backend = retainingConflictBackend()
    val key = MutationsTestKey("selector-${if (basePresent) "present" else "absent"}")
    val store = openConflictStore(storage, mutations, backend, ConflictPolicy(precondition = selector))
    try {
        if (basePresent) {
            backend.seed(key, "base")
            store.get(key, Freshness.MustBeFresh)
        }
        val mutationId = store.mutate(key, mutations.set, "mine")
        store.drain(key)
        return SelectorCell(
            attempt = storage.conflictState(mutationId).attempts.single(),
            push = backend.receivedPushes.single(),
        )
    } finally {
        store.close()
    }
}

private data class ConflictSequenceResult(
    val state: ConflictState,
    val receiptTimes: List<Long>,
)

private suspend fun runConflictSequence(metas: List<StoreMeta?>): ConflictSequenceResult {
    val storage = InMemoryMutationJournalStorage()
    val mutations = ConflictMutations()
    val backend = FakeBackend()
    val policy = retryMinePolicy()
    val clock = TestWallClock(100L)
    val receiptTimes = mutableListOf<Long>()
    var store = openConflictStore(storage, mutations, backend, policy, wallClock = clock)
    val key = MutationsTestKey("conflict-sequence-${metas.joinToString { it?.etag.orEmpty() }}")
    return try {
        val mutationId = store.mutate(key, mutations.set, "mine")
        backend.pushBehavior = { _, _ ->
            val meta = metas[backend.receivedPushes.size - 1]
            throw conflict(meta, "sequence conflict")
        }
        repeat(metas.size) { index ->
            store.drain(key)
            receiptTimes += clock.nowEpochMillis()
            if (index < metas.lastIndex) {
                clock.advanceBy(2.seconds)
                store.close()
                store = openConflictStore(storage, mutations, backend, policy, wallClock = clock)
            }
        }
        ConflictSequenceResult(
            state = storage.conflictState(mutationId),
            receiptTimes = receiptTimes,
        )
    } finally {
        store.close()
    }
}

private fun assertConflictReceipts(
    state: ConflictState,
    expected: List<StoreMeta?>,
    expectedReceiptTimes: List<Long>,
) {
    assertEquals(expected.size, state.attempts.size)
    assertEquals(expected.size, expectedReceiptTimes.size)
    state.attempts.sortedBy { it.generation }.zip(expected).forEachIndexed { index, (attempt, meta) ->
        assertEquals(meta != null, attempt.conflictMetaPresent)
        assertEquals(meta?.writtenAtEpochMillis, attempt.conflictWrittenAt)
        assertEquals(meta?.etag, attempt.conflictEtag)
        assertEquals(expectedReceiptTimes[index], attempt.conflictReceivedAt)
    }
}

private fun conflict(
    meta: StoreMeta?,
    message: String = "conflict",
): StoreException = conflictException(meta, message)

private fun conflictException(meta: StoreMeta?, message: String): StoreException =
    StoreResults.exception(
        StoreResults.conflict(meta, message),
        IllegalStateException("backend conflict cause"),
    )

private class ConflictTestMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String?,
) : StoreMeta

private class MutableMeta(
    override var writtenAtEpochMillis: Long,
    override var etag: String?,
) : StoreMeta

private data class ConflictState(
    val client: MutationClientRecord?,
    val intent: MutationIntentRecord,
    val execution: MutationExecutionRecord,
    val attempts: List<MutationAttemptRecord>,
    val failures: List<MutationFailureRecord>,
    val activeFailure: MutationFailureRecord?,
    val effects: List<MutationEffectRecord>,
    val acks: List<MutationAckRecord>,
    val aliases: List<MutationKeyAliasRecord>,
    val tombstones: List<MutationKeyTombstoneRecord>,
)

private suspend fun MutationJournalStorage.conflictState(mutationId: String): ConflictState =
    transaction { transaction ->
        val intent = transaction.intents(CONFLICT_CLIENT_ID).single { it.mutationId == mutationId }
        val execution =
            transaction.executions(CONFLICT_CLIENT_ID).single {
                it.clientSequence == intent.clientSequence
            }
        val failures =
            transaction.failures(CONFLICT_CLIENT_ID).filter {
                it.clientSequence == intent.clientSequence
            }
        ConflictState(
            client = transaction.client(CONFLICT_CLIENT_ID),
            intent = intent,
            execution = execution,
            attempts =
                transaction.attempts(CONFLICT_CLIENT_ID).filter {
                    it.clientSequence == intent.clientSequence
                },
            failures = failures,
            activeFailure = execution.activeFailureId?.let { id -> failures.single { it.failureId == id } },
            effects =
                transaction.effects(CONFLICT_CLIENT_ID).filter {
                    it.clientSequence == intent.clientSequence
                },
            acks =
                transaction.acks(CONFLICT_CLIENT_ID).filter {
                    it.clientSequence == intent.clientSequence
                },
            aliases = transaction.aliases(),
            tombstones = transaction.tombstones(),
        )
    }

private data class StateFingerprint(
    val phase: StoredPhase,
    val generation: Int,
    val attempt: Int,
    val lastAttemptAt: Long?,
    val activeFailureId: Long?,
    val attempts: List<AttemptFingerprint>,
    val failures: List<Pair<MutationFailureKind, String>>,
)

private data class AttemptFingerprint(
    val generation: Int,
    val base: List<Byte>?,
    val mine: List<Byte>?,
    val key: String,
    val conflictMetaPresent: Boolean?,
    val conflictWrittenAt: Long?,
    val conflictEtag: String?,
    val conflictReceivedAt: Long?,
)

private fun ConflictState.fingerprint(): StateFingerprint =
    StateFingerprint(
        execution.phase,
        execution.currentGeneration,
        execution.attempt,
        execution.lastAttemptAt,
        execution.activeFailureId,
        attempts.map {
            AttemptFingerprint(
                it.generation,
                it.baseBlob?.toList(),
                it.mineBlob?.toList(),
                it.generationIdempotencyKey,
                it.conflictMetaPresent,
                it.conflictWrittenAt,
                it.conflictEtag,
                it.conflictReceivedAt,
            )
        },
        failures.map { it.kind to it.detail },
    )

private data class FullFingerprint(
    val state: StateFingerprint,
    val pushCount: Int,
    val ackCount: Int,
    val effectFacts: List<Pair<MutationEffectDisposition, Long?>>,
)

private fun ConflictState.fullFingerprint(pushCount: Int): FullFingerprint =
    FullFingerprint(
        fingerprint(),
        pushCount,
        acks.size,
        effects.map { it.disposition to it.completedAt },
    )

private data class DeadLetterFingerprint(
    val mutationId: String,
    val generation: Int,
    val attempts: Int,
    val kind: MutationFailureKind,
    val detail: String,
)

private data class RefreshRequiredRestartCase(
    val name: String,
    val mutationId: String,
    val generation: Int,
    val attempt: Int,
    val lastAttemptAt: Long?,
    val immutableAttempt: AttemptFingerprint,
    val afterHydration: ConflictState,
    val hydrationDeadLetters: List<DeadLetterFingerprint>,
    val hydrationPushes: Int,
    val afterPass: ConflictState,
    val passDeadLetters: List<DeadLetterFingerprint>,
    val replayPushes: Int,
    val afterSecondReopen: ConflictState,
    val secondReopenDeadLetters: List<DeadLetterFingerprint>,
    val secondReopenPushes: Int,
)

private suspend fun runRefreshRequiredRestartCase(
    name: String,
    valueCodec: MutationCodec<String> = ConflictStringCodec,
    keyResolver: MutationKeyResolver<MutationsTestKey> = MutationsTestKeyResolver,
    drain: suspend (MutationStore<MutationsTestKey, String>, MutationsTestKey) -> Unit,
): RefreshRequiredRestartCase {
    val storage = InMemoryMutationJournalStorage()
    val mutations = ConflictMutations()
    val initialBackend = FakeBackend()
    val key = MutationsTestKey(name)
    val clock = TestWallClock(100L)
    val policy = retryMinePolicy()
    val initial = openConflictStore(storage, mutations, initialBackend, policy, wallClock = clock)
    val mutationId: String
    try {
        mutationId = initial.mutate(key, mutations.set, "mine-$name")
        initialBackend.pushBehavior = { _, _ -> throw conflict(ConflictTestMeta(12L, name)) }
        initial.drain(key)
    } finally {
        initial.close()
    }
    clock.advanceBy(2.seconds)
    val receipt = storage.conflictState(mutationId)
    val immutableAttempt = receipt.attempts.single().restartFingerprint()
    val replayBackend = FakeBackend()
    val restarted =
        openConflictStore(
            storage,
            mutations,
            replayBackend,
            policy,
            wallClock = clock,
            keyResolver = keyResolver,
            valueCodec = valueCodec,
        )
    val afterHydration: ConflictState
    val hydrationDeadLetters: List<DeadLetterFingerprint>
    val hydrationPushes: Int
    val afterPass: ConflictState
    val passDeadLetters: List<DeadLetterFingerprint>
    try {
        restarted.pendingWrites()
        afterHydration = storage.conflictState(mutationId)
        hydrationDeadLetters = restarted.deadLetters().map(DeadLetter::restartFingerprint)
        hydrationPushes = replayBackend.receivedPushes.size
        drain(restarted, key)
        afterPass = storage.conflictState(mutationId)
        passDeadLetters = restarted.deadLetters().map(DeadLetter::restartFingerprint)
    } finally {
        restarted.close()
    }
    val secondReopenBackend = FakeBackend()
    val secondReopen =
        openConflictStore(
            storage,
            mutations,
            secondReopenBackend,
            policy,
            wallClock = clock,
        )
    val afterSecondReopen: ConflictState
    val secondReopenDeadLetters: List<DeadLetterFingerprint>
    try {
        secondReopenDeadLetters = secondReopen.deadLetters().map(DeadLetter::restartFingerprint)
        afterSecondReopen = storage.conflictState(mutationId)
    } finally {
        secondReopen.close()
    }
    return RefreshRequiredRestartCase(
        name = name,
        mutationId = mutationId,
        generation = receipt.execution.currentGeneration,
        attempt = receipt.execution.attempt,
        lastAttemptAt = receipt.execution.lastAttemptAt,
        immutableAttempt = immutableAttempt,
        afterHydration = afterHydration,
        hydrationDeadLetters = hydrationDeadLetters,
        hydrationPushes = hydrationPushes,
        afterPass = afterPass,
        passDeadLetters = passDeadLetters,
        replayPushes = replayBackend.receivedPushes.size,
        afterSecondReopen = afterSecondReopen,
        secondReopenDeadLetters = secondReopenDeadLetters,
        secondReopenPushes = secondReopenBackend.receivedPushes.size,
    )
}

private fun assertRefreshRequiredRestartCases(
    codec: RefreshRequiredRestartCase,
    identity: RefreshRequiredRestartCase,
) {
    val failures = mutableListOf<String>()
    listOf(
        codec to (MutationFailureKind.CODEC to "value-codec-pre-ack"),
        identity to (MutationFailureKind.IDENTITY to DRAIN_FAILURE_DETAIL_RESOLVER_NULL),
    ).forEach { (observation, expected) ->
        try {
            assertRefreshRequiredRestartCase(
                observation = observation,
                kind = expected.first,
                detail = expected.second,
            )
        } catch (failure: AssertionError) {
            failures += failure.message.orEmpty()
        }
    }
    assertEquals(emptyList<String>(), failures)
}

private fun assertRefreshRequiredRestartCase(
    observation: RefreshRequiredRestartCase,
    kind: MutationFailureKind,
    detail: String,
) {
    with(observation) {
        assertEquals(StoredPhase.REFRESH_REQUIRED, afterHydration.execution.phase, name)
        assertEquals(generation, afterHydration.execution.currentGeneration, name)
        assertEquals(attempt, afterHydration.execution.attempt, name)
        assertEquals(lastAttemptAt, afterHydration.execution.lastAttemptAt, name)
        assertEquals(immutableAttempt, afterHydration.attempts.single().restartFingerprint(), name)
        assertEquals(emptyList(), afterHydration.failures, name)
        assertNull(afterHydration.activeFailure, name)
        assertEquals(emptyList(), hydrationDeadLetters, name)
        assertEquals(0, hydrationPushes, name)

        assertEquals(StoredPhase.PARKED, afterPass.execution.phase, name)
        assertEquals(generation, afterPass.execution.currentGeneration, name)
        assertEquals(attempt, afterPass.execution.attempt, name)
        assertEquals(lastAttemptAt, afterPass.execution.lastAttemptAt, name)
        assertEquals(immutableAttempt, afterPass.attempts.single().restartFingerprint(), name)
        assertEquals(1, afterPass.failures.size, name)
        val activeFailure = assertNotNull(afterPass.activeFailure, name)
        assertEquals(afterPass.failures.single().failureId, activeFailure.failureId, name)
        assertEquals(generation, activeFailure.generation, name)
        assertEquals(kind, activeFailure.kind, name)
        assertEquals(detail, activeFailure.detail, name)
        assertEquals(0, replayPushes, name)
        assertEquals(
            listOf(DeadLetterFingerprint(mutationId, generation, attempt, kind, detail)),
            passDeadLetters,
            name,
        )

        assertEquals(StoredPhase.PARKED, afterSecondReopen.execution.phase, name)
        assertEquals(generation, afterSecondReopen.execution.currentGeneration, name)
        assertEquals(attempt, afterSecondReopen.execution.attempt, name)
        assertEquals(lastAttemptAt, afterSecondReopen.execution.lastAttemptAt, name)
        assertEquals(immutableAttempt, afterSecondReopen.attempts.single().restartFingerprint(), name)
        assertEquals(1, afterSecondReopen.failures.size, name)
        assertEquals(
            listOf(DeadLetterFingerprint(mutationId, generation, attempt, kind, detail)),
            secondReopenDeadLetters,
            name,
        )
        assertEquals(0, secondReopenPushes, name)
    }
}

private fun MutationAttemptRecord.restartFingerprint(): AttemptFingerprint =
    AttemptFingerprint(
        generation = generation,
        base = baseBlob?.toList(),
        mine = mineBlob?.toList(),
        key = generationIdempotencyKey,
        conflictMetaPresent = conflictMetaPresent,
        conflictWrittenAt = conflictWrittenAt,
        conflictEtag = conflictEtag,
        conflictReceivedAt = conflictReceivedAt,
    )

private fun DeadLetter.restartFingerprint(): DeadLetterFingerprint =
    DeadLetterFingerprint(
        mutationId = mutationId,
        generation = generation,
        attempts = attempts,
        kind = failure.kind,
        detail = failure.detail,
    )

private fun MutationPresence<String>.toState(): MutationPresenceState =
    if (this is MutationPresence.Present) MutationPresenceState.PRESENT else MutationPresenceState.ABSENT

private fun MutationPresence<String>.encode(codec: MutationCodec<String>): ByteArray? =
    (this as? MutationPresence.Present)?.let { codec.encode(it.value) }

private fun MutationPresence<String>.valueOrAbsent(): String =
    (this as? MutationPresence.Present)?.value ?: "absent"

private class CaptureTrace {
    val events = mutableListOf<String>()
    var afterBarrier: Boolean = false
}

private class TracingSourceOfTruth(
    private val trace: CaptureTrace,
) : SourceOfTruth<MutationsTestKey, String> {
    private val rows = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun reader(key: MutationsTestKey): Flow<String?> =
        rows.map { values ->
            if (trace.afterBarrier) trace.events += "local"
            values[key.canonicalId()]
        }

    override suspend fun write(key: MutationsTestKey, value: String) {
        rows.update { it + (key.canonicalId() to value) }
    }

    override suspend fun delete(key: MutationsTestKey) {
        rows.update { it - key.canonicalId() }
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        rows.value = emptyMap()
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

private class TracingBookkeeper(
    private val trace: CaptureTrace,
    private val delegate: MutationBookkeeper = MutationBookkeeper(),
) : Bookkeeper {
    override suspend fun recordSuccess(key: StoreKey, meta: StoreMeta) = delegate.recordSuccess(key, meta)

    override suspend fun recordFailure(key: StoreKey, atEpochMillis: Long) =
        delegate.recordFailure(key, atEpochMillis)

    override suspend fun status(key: StoreKey): KeyStatus? {
        if (trace.afterBarrier) trace.events += "status"
        return delegate.status(key)
    }

    override suspend fun forget(key: StoreKey) = delegate.forget(key)

    override suspend fun markStale(key: StoreKey) = delegate.markStale(key)

    override suspend fun advanceStaleWatermark(namespace: StoreNamespace) =
        delegate.advanceStaleWatermark(namespace)

    override suspend fun advanceGlobalStaleWatermark() = delegate.advanceGlobalStaleWatermark()

    override suspend fun forgetNamespace(namespace: StoreNamespace) = delegate.forgetNamespace(namespace)

    override suspend fun forgetAll() = delegate.forgetAll()
}

private class CountingBookkeeper(
    private val delegate: MutationBookkeeper = MutationBookkeeper(),
) : Bookkeeper {
    var markStaleCalls: Int = 0
        private set

    override suspend fun recordSuccess(key: StoreKey, meta: StoreMeta) = delegate.recordSuccess(key, meta)

    override suspend fun recordFailure(key: StoreKey, atEpochMillis: Long) =
        delegate.recordFailure(key, atEpochMillis)

    override suspend fun status(key: StoreKey): KeyStatus? = delegate.status(key)

    override suspend fun forget(key: StoreKey) = delegate.forget(key)

    override suspend fun markStale(key: StoreKey) {
        markStaleCalls += 1
        delegate.markStale(key)
    }

    override suspend fun advanceStaleWatermark(namespace: StoreNamespace) =
        delegate.advanceStaleWatermark(namespace)

    override suspend fun advanceGlobalStaleWatermark() = delegate.advanceGlobalStaleWatermark()

    override suspend fun forgetNamespace(namespace: StoreNamespace) = delegate.forgetNamespace(namespace)

    override suspend fun forgetAll() = delegate.forgetAll()
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
