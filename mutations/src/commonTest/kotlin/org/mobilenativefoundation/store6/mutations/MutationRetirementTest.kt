@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAckRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationAliasState
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationIntentRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalTransaction
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyAliasRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyTombstoneRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationTombstoneState
import org.mobilenativefoundation.store6.testing.TestWallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutationRetirementTest {
    @Test
    fun consecutiveDeletes_keepOldActiveUntilNewDeleteRetires() = runTest {
        val storage = FailPointJournalStorage(InMemoryMutationJournalStorage())
        val mutations = RetirementMutations()
        val backend = retainingRetirementBackend()
        val handle = RecordingRetirementHandle()
        val clock = TestWallClock(1_000L)
        val key = MutationsTestKey("consecutive-delete")
        val engine = openRetirementEngine(storage, mutations, backend, handle, clock)

        val firstDelete = engine.mutate(key, mutations.deleteRef, Unit)
        engine.drain(key)
        val firstRetired = storage.retirementState()
        val firstSequence = firstRetired.sequenceOf(firstDelete)
        val firstActive = firstRetired.tombstoneCreatedBy(firstSequence)
        assertActiveTombstone(firstActive, firstRetired.executionOf(firstDelete).retiredAt)

        clock.setEpochMillis(2_000L)
        val secondDelete = engine.mutate(key, mutations.deleteRef, Unit)
        storage.armFailTransaction(JournalFailPointBoundary.ADOPTION_ADVANCE)
        assertIs<FailPointTransactionException>(captureRetirementFailure { engine.drain(key) })

        val held = storage.retirementState()
        val secondSequence = held.sequenceOf(secondDelete)
        assertEquals(StoredPhase.ACKED, held.executionOf(secondDelete).phase)
        assertActiveTombstone(held.tombstoneCreatedBy(firstSequence), firstActive.activatedAt)
        assertPendingTombstone(held.tombstoneCreatedBy(secondSequence))

        val errors = mutableListOf<String>()
        val finalizationFailure = captureRetirementFailure { engine.drain(key) }
        expectRetirement(errors, "the second delete finalizes") {
            assertNull(finalizationFailure)
        }
        val completed = storage.retirementState()
        expectRetirement(errors, "the first generation is superseded by the second delete") {
            val retiredAt = assertNotNull(completed.executionOf(secondDelete).retiredAt)
            assertSupersededTombstone(
                record = completed.tombstoneCreatedBy(firstSequence),
                successorSequence = secondSequence,
                activatedAt = assertNotNull(firstActive.activatedAt),
                supersededAt = retiredAt,
            )
        }
        expectRetirement(errors, "the second delete generation becomes active") {
            val retiredAt = completed.executionOf(secondDelete).retiredAt
            assertActiveTombstone(completed.tombstoneCreatedBy(secondSequence), retiredAt)
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun newDeleteRetirement_atomicallySupersedesOldAndActivatesNew() = runTest {
        val storage = FailPointJournalStorage(InMemoryMutationJournalStorage())
        val mutations = RetirementMutations()
        val backend = retainingRetirementBackend()
        val handle = RecordingRetirementHandle()
        val clock = TestWallClock(3_000L)
        val key = MutationsTestKey("atomic-delete-retirement")
        val engine = openRetirementEngine(storage, mutations, backend, handle, clock)

        val firstDelete = engine.mutate(key, mutations.deleteRef, Unit)
        engine.drain(key)
        clock.setEpochMillis(4_000L)
        val secondDelete = engine.mutate(key, mutations.deleteRef, Unit)
        storage.armKillBeforeCommit(JournalFailPointBoundary.FINALIZATION)

        val errors = mutableListOf<String>()
        val interruptedFailure = captureRetirementFailure { engine.drain(key) }
        expectRetirement(errors, "the finalization fail point kills before commit") {
            val death = assertIs<FailPointProcessDeathException>(interruptedFailure)
            assertEquals(false, death.committed)
            assertEquals(listOf(JournalFailPointBoundary.FINALIZATION), storage.triggeredBoundaries)
        }

        val interrupted = storage.retirementState()
        val firstSequence = interrupted.sequenceOf(firstDelete)
        val secondSequence = interrupted.sequenceOf(secondDelete)
        expectRetirement(errors, "no half-superseded tombstone state survives restart") {
            assertEquals(StoredPhase.EFFECTS_PENDING, interrupted.executionOf(secondDelete).phase)
            assertActiveTombstone(
                interrupted.tombstoneCreatedBy(firstSequence),
                interrupted.executionOf(firstDelete).retiredAt,
            )
            assertPendingTombstone(interrupted.tombstoneCreatedBy(secondSequence))
        }

        val reopened = openRetirementEngine(storage, mutations, backend, handle, clock)
        val cleanFailure = captureRetirementFailure { reopened.drain(key) }
        expectRetirement(errors, "a clean restart completes finalization") {
            assertNull(cleanFailure)
        }
        val completed = storage.retirementState()
        expectRetirement(errors, "one committed transaction links and activates both generations") {
            val retiredAt = assertNotNull(completed.executionOf(secondDelete).retiredAt)
            assertSupersededTombstone(
                record = completed.tombstoneCreatedBy(firstSequence),
                successorSequence = secondSequence,
                activatedAt = assertNotNull(interrupted.tombstoneCreatedBy(firstSequence).activatedAt),
                supersededAt = retiredAt,
            )
            assertActiveTombstone(completed.tombstoneCreatedBy(secondSequence), retiredAt)
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun restartBetweenAbsentAckAndRetirement_preservesOldActiveAndNewPending() = runTest {
        val storage = FailPointJournalStorage(InMemoryMutationJournalStorage())
        val mutations = RetirementMutations()
        val backend = retainingRetirementBackend()
        val handle = RecordingRetirementHandle()
        val clock = TestWallClock(5_000L)
        val key = MutationsTestKey("restart-after-absent-ack")
        val first = openRetirementEngine(storage, mutations, backend, handle, clock)

        val firstDelete = first.mutate(key, mutations.deleteRef, Unit)
        first.drain(key)
        clock.setEpochMillis(6_000L)
        val secondDelete = first.mutate(key, mutations.deleteRef, Unit)
        storage.armKillAfterCommit(JournalFailPointBoundary.ACK_RECEIPT)

        val death =
            assertIs<FailPointProcessDeathException>(
                captureRetirementFailure { first.drain(key) },
            )
        assertTrue(death.committed)
        assertEquals(listOf(JournalFailPointBoundary.ACK_RECEIPT), storage.triggeredBoundaries)

        val interrupted = storage.retirementState()
        val firstSequence = interrupted.sequenceOf(firstDelete)
        val secondSequence = interrupted.sequenceOf(secondDelete)
        assertEquals(StoredPhase.ACKED, interrupted.executionOf(secondDelete).phase)
        assertActiveTombstone(
            interrupted.tombstoneCreatedBy(firstSequence),
            interrupted.executionOf(firstDelete).retiredAt,
        )
        assertPendingTombstone(interrupted.tombstoneCreatedBy(secondSequence))

        val errors = mutableListOf<String>()
        val reopened = openRetirementEngine(storage, mutations, backend, handle, clock)
        val cleanFailure = captureRetirementFailure { reopened.drain(key) }
        expectRetirement(errors, "the restarted drain completes without another push") {
            assertNull(cleanFailure)
            assertEquals(2, backend.receivedPushes.size)
        }
        val completed = storage.retirementState()
        expectRetirement(errors, "restart supersedes the old generation and activates the pending one") {
            val retiredAt = assertNotNull(completed.executionOf(secondDelete).retiredAt)
            assertSupersededTombstone(
                record = completed.tombstoneCreatedBy(firstSequence),
                successorSequence = secondSequence,
                activatedAt = assertNotNull(interrupted.tombstoneCreatedBy(firstSequence).activatedAt),
                supersededAt = retiredAt,
            )
            assertActiveTombstone(completed.tombstoneCreatedBy(secondSequence), retiredAt)
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun presentThenDelete_createsNewGeneration() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = RetirementMutations()
        val backend = retainingRetirementBackend()
        val handle = RecordingRetirementHandle()
        val clock = TestWallClock(7_000L)
        val key = MutationsTestKey("delete-present-delete")
        val engine = openRetirementEngine(storage, mutations, backend, handle, clock)

        val firstDelete = engine.mutate(key, mutations.deleteRef, Unit)
        engine.drain(key)
        clock.setEpochMillis(8_000L)
        val present = engine.mutate(key, mutations.set, "restored")
        engine.drain(key)

        val errors = mutableListOf<String>()
        val afterPresent = storage.retirementState()
        val firstSequence = afterPresent.sequenceOf(firstDelete)
        val presentSequence = afterPresent.sequenceOf(present)
        expectRetirement(errors, "the Present retirement supersedes the active delete generation") {
            assertSupersededTombstone(
                record = afterPresent.tombstoneCreatedBy(firstSequence),
                successorSequence = presentSequence,
                activatedAt = 7_000L,
                supersededAt = assertNotNull(afterPresent.executionOf(present).retiredAt),
            )
        }

        clock.setEpochMillis(9_000L)
        val secondDelete = engine.mutate(key, mutations.deleteRef, Unit)
        val secondDeleteFailure = captureRetirementFailure { engine.drain(key) }
        expectRetirement(errors, "the post-Present delete retires cleanly") {
            assertNull(secondDeleteFailure)
        }

        val completed = storage.retirementState()
        val secondDeleteSequence = completed.sequenceOf(secondDelete)
        expectRetirement(errors, "delete-present-delete retains two distinct tombstone generations") {
            assertNotEquals(firstSequence, secondDeleteSequence)
            assertEquals(
                setOf(firstSequence, secondDeleteSequence),
                completed.tombstones.map { it.createdBySequence }.toSet(),
            )
            assertEquals(
                emptyList(),
                completed.tombstones.filter { it.createdBySequence == presentSequence },
            )
        }
        expectRetirement(errors, "the first generation keeps its Present successor linkage") {
            assertSupersededTombstone(
                record = completed.tombstoneCreatedBy(firstSequence),
                successorSequence = presentSequence,
                activatedAt = 7_000L,
                supersededAt = assertNotNull(completed.executionOf(present).retiredAt),
            )
        }
        expectRetirement(errors, "the later delete owns the new active generation") {
            val retiredAt = completed.executionOf(secondDelete).retiredAt
            assertActiveTombstone(completed.tombstoneCreatedBy(secondDeleteSequence), retiredAt)
            assertEquals(3L, assertNotNull(completed.client).retiredThroughSequence)
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun parkedSequence_pinsRetiredPrefix_butNotSameKeyExecution() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = RetirementMutations()
        val backend = FakeBackend()
        val handle = RecordingRetirementHandle()
        val clock = TestWallClock(10_000L)
        val key = MutationsTestKey("retirement-gap")
        val resolver =
            MutationKeyResolver<MutationsTestKey> {
                throw IllegalStateException("park the first sequence")
            }
        val engine = openRetirementEngine(storage, mutations, backend, handle, clock, resolver)

        val parked = engine.mutate(key, mutations.append, "+parked")
        val delete = engine.mutate(key, mutations.deleteRef, Unit)
        val present = engine.mutate(key, mutations.set, "restored")
        engine.clearLiveKeyCache()
        engine.drain()
        engine.drain(key)

        val beforeRestart = storage.retirementState()
        assertEquals(StoredPhase.PARKED, beforeRestart.executionOf(parked).phase)
        assertEquals(StoredPhase.RETIRED, beforeRestart.executionOf(delete).phase)
        assertEquals(StoredPhase.RETIRED, beforeRestart.executionOf(present).phase)
        assertEquals(0L, assertNotNull(beforeRestart.client).retiredThroughSequence)
        assertTrue(beforeRestart.executionOf(delete).retiredAt != null)
        assertTrue(beforeRestart.executionOf(present).retiredAt != null)
        assertEquals(listOf(0L, 0L), backend.receivedPushes.map { it.retiredThroughSequence })
        assertEquals(listOf("restored"), backend.pushedValues)

        val reopened = openRetirementEngine(storage, mutations, backend, handle, clock)
        assertEquals(listOf(parked), reopened.deadLetters().map { it.mutationId })
        assertEquals(emptyList(), reopened.pendingWrites())
        val afterRestart = storage.retirementState()
        assertEquals(
            beforeRestart.accountingFor(parked, delete, present),
            afterRestart.accountingFor(parked, delete, present),
        )
    }

    @Test
    fun keylessDrain_confirmsCurrentPrefixAfterFinalLocalRetirement() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = RetirementMutations()
        val backend = FakeBackend()
        val handle = RecordingRetirementHandle()
        val clock = TestWallClock(11_000L)
        val key = MutationsTestKey("checkpoint-keyless")
        backend.retireBehavior = { request ->
            MutationRetirementAck(
                confirmedThroughSequence =
                    if (backend.retirementRequests.size == 1) {
                        0L
                    } else {
                        request.retiredThroughSequence
                    },
            )
        }
        val engine = openRetirementEngine(storage, mutations, backend, handle, clock)

        engine.mutate(key, mutations.set, "retired")
        engine.drain(key)

        val afterLocal = assertNotNull(storage.transaction { it.client(RETIREMENT_CLIENT_ID) })
        assertEquals(1L, afterLocal.retiredThroughSequence)
        assertEquals(0L, afterLocal.serverConfirmedRetiredThroughSequence)
        assertEquals(listOf(1L), backend.retirementRequests.map { it.retiredThroughSequence })

        engine.drain()

        val afterKeyless = assertNotNull(storage.transaction { it.client(RETIREMENT_CLIENT_ID) })
        assertEquals(1L, afterKeyless.retiredThroughSequence)
        assertEquals(1L, afterKeyless.serverConfirmedRetiredThroughSequence)
        assertEquals(listOf(1L, 1L), backend.retirementRequests.map { it.retiredThroughSequence })
        assertTrue(storage.transaction { it.intents(RETIREMENT_CLIENT_ID).isEmpty() })
        assertTrue(storage.transaction { it.executions(RETIREMENT_CLIENT_ID).isEmpty() })

        val reopened = openRetirementEngine(storage, mutations, backend, handle, clock)
        reopened.ensureHydrated()
        val afterRestart = assertNotNull(storage.transaction { it.client(RETIREMENT_CLIENT_ID) })
        assertEquals(1L, afterRestart.serverConfirmedRetiredThroughSequence)
        assertTrue(reopened.pendingWrites().isEmpty())
    }

    @Test
    fun localHighWaterStopsAtEveryUnretiredSequenceGap() = runTest {
        val gaps =
            listOf(
                CheckpointGap.PARKED,
                CheckpointGap.ACTIVE,
                CheckpointGap.EFFECTS_PENDING,
            )

        gaps.forEach { gap ->
            val storage = InMemoryMutationJournalStorage()
            seedCheckpointGap(storage, gap)
            val mutations = RetirementMutations()
            val backend = FakeBackend()
            backend.retireBehavior = { MutationRetirementAck(confirmedThroughSequence = 0L) }
            val engine =
                openRetirementEngine(
                    storage = storage,
                    mutations = mutations,
                    backend = backend,
                    handle = RecordingRetirementHandle(),
                    clock = TestWallClock(12_000L),
                    resolver = RETIREMENT_ANY_NAMESPACE_RESOLVER,
                )

            engine.ensureHydrated()
            engine.drain(
                MutationsTestKey(
                    id = "checkpoint-probe-${gap.name.lowercase()}",
                    namespace = StoreNamespace("checkpoint-probe"),
                ),
            )

            val client = assertNotNull(storage.transaction { it.client(RETIREMENT_CLIENT_ID) })
            assertEquals(1L, client.retiredThroughSequence, gap.name)
            assertEquals(
                listOf(1L),
                backend.retirementRequests.map { request -> request.retiredThroughSequence },
                gap.name,
            )
            val executions = storage.transaction { it.executions(RETIREMENT_CLIENT_ID) }
            assertEquals(gap.phase, executions.single { it.clientSequence == 2L }.phase, gap.name)
            assertEquals(StoredPhase.RETIRED, executions.single { it.clientSequence == 3L }.phase)
        }
    }

    /**
     * Ordinary prune removes rows only at or below the persisted server-confirmed prefix;
     * alias redirects and active or pending tombstone generations always survive.
     */
    @Test
    fun pruningNeverExceedsServerConfirmedRetiredPrefix() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = RetirementMutations()
        val backend = FakeBackend()
        val handle = RecordingRetirementHandle()
        val source = MutationsTestKey("checkpoint-prune-source")
        val canonical = MutationsTestKey("checkpoint-prune-canonical")
        backend.redirectPresent(source, canonical)
        backend.retireBehavior = { request ->
            MutationRetirementAck(minOf(1L, request.retiredThroughSequence))
        }
        val engine =
            openRetirementEngine(
                storage,
                mutations,
                backend,
                handle,
                TestWallClock(13_000L),
            )

        engine.mutate(source, mutations.set, "present")
        engine.drain(source)
        engine.mutate(canonical, mutations.deleteRef, Unit)
        engine.drain(canonical)

        val durable = storage.retirementState()
        val client = assertNotNull(durable.client)
        assertEquals(2L, client.retiredThroughSequence)
        assertEquals(1L, client.serverConfirmedRetiredThroughSequence)
        assertEquals(listOf(1L, 2L), backend.retirementRequests.map { it.retiredThroughSequence })
        assertEquals(listOf(2L), durable.intents.map { it.clientSequence })
        assertEquals(listOf(2L), durable.executions.map { it.clientSequence })
        assertEquals(MutationAliasState.ACTIVE, durable.aliases.single().state)
        val activeTombstone = durable.tombstones.single()
        assertEquals(MutationTombstoneState.ACTIVE, activeTombstone.state)
        assertEquals(2L, activeTombstone.createdBySequence)
    }

    @Test
    fun retirementCancellation_rethrowsWithoutConfirmingOrPruning_andRetriesMonotonically() =
        runTest {
            val storage = InMemoryMutationJournalStorage()
            val mutations = RetirementMutations()
            val backend = FakeBackend()
            val handle = RecordingRetirementHandle()
            val key = MutationsTestKey("checkpoint-cancel")
            var cancelNext = true
            backend.retireBehavior = { request ->
                if (cancelNext) {
                    cancelNext = false
                    throw CancellationException("checkpoint outcome unknown")
                }
                MutationRetirementAck(request.retiredThroughSequence)
            }
            val engine =
                openRetirementEngine(
                    storage,
                    mutations,
                    backend,
                    handle,
                    TestWallClock(14_000L),
                )
            engine.mutate(key, mutations.set, "retired")

            assertFailsWith<CancellationException> { engine.drain(key) }

            val cancelled = assertNotNull(storage.transaction { it.client(RETIREMENT_CLIENT_ID) })
            assertEquals(1L, cancelled.retiredThroughSequence)
            assertEquals(0L, cancelled.serverConfirmedRetiredThroughSequence)
            assertEquals(listOf(1L), backend.retirementRequests.map { it.retiredThroughSequence })
            assertEquals(1, storage.transaction { it.intents(RETIREMENT_CLIENT_ID).size })

            engine.drain()

            val retried = assertNotNull(storage.transaction { it.client(RETIREMENT_CLIENT_ID) })
            assertEquals(1L, retried.serverConfirmedRetiredThroughSequence)
            assertEquals(listOf(1L, 1L), backend.retirementRequests.map { it.retiredThroughSequence })
            assertTrue(storage.transaction { it.intents(RETIREMENT_CLIENT_ID).isEmpty() })
        }

    @Test
    fun checkpointFailureEmitsClientScopedEventWithoutMutationIdentity() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = RetirementMutations()
        val backend = FakeBackend()
        val key = MutationsTestKey("checkpoint-failure")
        backend.retireBehavior = {
            throw IllegalStateException("retirement transport failed\nraw throwable context")
        }
        val engine =
            openRetirementEngine(
                storage,
                mutations,
                backend,
                RecordingRetirementHandle(),
                TestWallClock(15_000L),
            )
        engine.mutate(key, mutations.set, "retired")
        val event =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.eventBus.events.filterIsInstance<MutationCheckpointFailed>().first()
            }

        engine.drain(key)
        runCurrent()

        assertTrue(event.isCompleted, "checkpoint failure event was not emitted")
        val failed = event.await()
        val root: MutationEvent = failed
        assertFalse(root is MutationIntentEvent)
        assertEquals(RETIREMENT_CLIENT_ID, failed.clientId)
        assertEquals(1L, failed.requestedThroughSequence)
        assertEquals(MutationFailureKind.TRANSPORT, failed.failure.kind)
        assertEquals("retirement transport failed", failed.failure.message)
        assertFalse(failed.failure.message.contains("Throwable"))
        assertFalse(failed.failure.message.contains('\n'))
        assertTrue(storage.transaction { it.failures(RETIREMENT_CLIENT_ID).isEmpty() })
        val client = assertNotNull(storage.transaction { it.client(RETIREMENT_CLIENT_ID) })
        assertEquals(1L, client.retiredThroughSequence)
        assertEquals(0L, client.serverConfirmedRetiredThroughSequence)
    }

    @Test
    fun checkpointSuccessEmitsPersistedConfirmedPrefix() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = RetirementMutations()
        val backend = FakeBackend()
        val key = MutationsTestKey("checkpoint-success")
        backend.retireBehavior = { request ->
            MutationRetirementAck(
                confirmedThroughSequence = minOf(1L, request.retiredThroughSequence),
            )
        }
        val engine =
            openRetirementEngine(
                storage,
                mutations,
                backend,
                RecordingRetirementHandle(),
                TestWallClock(16_000L),
            )
        engine.mutate(key, mutations.set, "first")
        engine.mutate(key, mutations.set, "second")
        val event =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.eventBus.events.filterIsInstance<MutationCheckpointConfirmed>().first()
            }

        engine.drain(key)
        runCurrent()

        assertTrue(event.isCompleted, "checkpoint success event was not emitted")
        val confirmed = event.await()
        val persisted = assertNotNull(storage.transaction { it.client(RETIREMENT_CLIENT_ID) })
        assertEquals(RETIREMENT_CLIENT_ID, confirmed.clientId)
        assertEquals(listOf(2L), backend.retirementRequests.map { it.retiredThroughSequence })
        assertEquals(2L, confirmed.requestedThroughSequence)
        assertEquals(1L, persisted.serverConfirmedRetiredThroughSequence)
        assertEquals(persisted.serverConfirmedRetiredThroughSequence, confirmed.confirmedThroughSequence)
        assertEquals(1L, confirmed.confirmedThroughSequence)
        assertTrue(storage.transaction { it.failures(RETIREMENT_CLIENT_ID).isEmpty() })
    }

    @Test
    fun tombstoneReplayFollowsCanonicalAlias() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutations = RetirementMutations()
        val source = MutationsTestKey("replay-source")
        val middle = MutationsTestKey("replay-middle")
        val canonical = MutationsTestKey("replay-canonical")
        seedTombstoneReplay(storage, mutations, source, middle, canonical)
        val engine =
            openRetirementEngine(
                storage = storage,
                mutations = mutations,
                backend = FakeBackend(),
                handle = RecordingRetirementHandle(),
                clock = TestWallClock(20_000L),
            )

        engine.ensureHydrated()
        val errors = mutableListOf<String>()
        expectRetirement(errors, "the source alias resolves to the tombstone identity") {
            assertEquals(canonical.identity(), engine.terminalIdentityOf(source.identity()))
        }
        expectRetirement(errors, "the middle alias resolves to the tombstone identity") {
            assertEquals(canonical.identity(), engine.terminalIdentityOf(middle.identity()))
        }
        expectRetirement(errors, "the canonical identity remains terminal") {
            assertEquals(canonical.identity(), engine.terminalIdentityOf(canonical.identity()))
        }
        val expectedReplay = listOf(POST_TOMBSTONE_MUTATION_ID)
        expectRetirement(errors, "source pending follows the active tombstone through both aliases") {
            assertEquals(expectedReplay, engine.pending(source).map { it.mutationId })
        }
        expectRetirement(errors, "middle pending follows the active tombstone through its alias") {
            assertEquals(expectedReplay, engine.pending(middle).map { it.mutationId })
        }
        expectRetirement(errors, "canonical pending excludes only the pre-watermark replay") {
            assertEquals(expectedReplay, engine.pending(canonical).map { it.mutationId })
        }
        expectRetirement(errors, "global pending retains the post-watermark control") {
            assertEquals(expectedReplay, engine.pendingWrites().map { it.mutationId })
        }
        expectRetirement(errors, "overlay replay applies only the post-watermark mutation") {
            assertEquals("base+fresh", engine.overlay.apply(canonical, "base"))
        }
        expectRetirement(
            errors,
            "a lower-sequence causal Present supersedes the retired Delete and preserves interposed rows",
        ) {
            assertLowerSequenceCausalPresentRehomesInterposedRows()
        }
        expectRetirement(errors, "a lower-sequence causal Present replaces a retired target Present") {
            assertRetiredTargetPresentCanBeCausallyReplaced()
        }
        expectRetirement(errors, "lost-ack restart retains unique namespace authority") {
            assertLostAckOwnerSurvivesRestart()
        }
        expectRetirement(errors, "ACKED restart resumes without repushing the acknowledged generation") {
            assertAckedRestartDoesNotRepush()
        }
        expectRetirement(errors, "alias fan-in supersedes every collapsed active tombstone") {
            assertAliasFanInSupersedesEveryCollapsedWatermark()
        }
        expectRetirement(errors, "kill-before and kill-after finalization remain restart-safe") {
            assertFinalizationKillWindows()
        }
        expectRetirement(errors, "retirement publishes routing, membership, and tombstones coherently") {
            assertAliasRetirementPublicationIsCoherent()
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }
}

private class RetirementMutations {
    lateinit var set: MutatorRef<MutationsTestKey, String, String>
    lateinit var append: MutatorRef<MutationsTestKey, String, String>
    lateinit var park: MutatorRef<MutationsTestKey, String, String>
    lateinit var deleteRef: MutatorRef<MutationsTestKey, String, Unit>

    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            set =
                mutator(
                    id = "retirement-set",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { _, value -> MutationPresence.Present(value) }
            append =
                mutator(
                    id = "retirement-append",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { base, suffix ->
                    MutationPresence.Present(
                        (base as? MutationPresence.Present)?.value.orEmpty() + suffix,
                    )
                }
            park =
                mutator(
                    id = "retirement-park",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { _, _ ->
                    throw IllegalStateException("park the interposed mutation")
                }
            deleteRef = delete(id = "retirement-delete", stales = noStales())
        }
}

private class RecordingRetirementHandle : StoreWriteHandle<MutationsTestKey, String> {
    private val values = mutableMapOf<KeyIdentity, String>()
    private val confirmedAbsent = mutableSetOf<KeyIdentity>()

    fun read(key: MutationsTestKey): String? =
        if (key.identity() in confirmedAbsent) null else values[key.identity()] ?: "base"

    fun valueAt(key: MutationsTestKey): String? = read(key)

    override suspend fun apply(
        key: MutationsTestKey,
        value: String,
    ) {
        values[key.identity()] = value
        confirmedAbsent -= key.identity()
    }

    override suspend fun markStale(key: MutationsTestKey) = Unit

    override suspend fun confirmFresh(
        key: MutationsTestKey,
        etag: String?,
    ) = Unit

    fun recordClear(key: MutationsTestKey) {
        values.remove(key.identity())
        confirmedAbsent += key.identity()
    }
}

private fun openRetirementEngine(
    storage: MutationJournalStorage,
    mutations: RetirementMutations,
    backend: FakeBackend,
    handle: RecordingRetirementHandle,
    clock: TestWallClock,
    resolver: MutationKeyResolver<MutationsTestKey> = MutationsTestKeyResolver,
): MutationEngine<MutationsTestKey, String> =
    openRetirementFixture(storage, mutations, backend, handle, clock, resolver).engine

private data class RetirementEngineFixture(
    val engine: MutationEngine<MutationsTestKey, String>,
    val journal: StorageBackedMutationJournal<String>,
)

private fun openRetirementFixture(
    storage: MutationJournalStorage,
    mutations: RetirementMutations,
    backend: FakeBackend,
    handle: RecordingRetirementHandle,
    clock: TestWallClock,
    resolver: MutationKeyResolver<MutationsTestKey> = MutationsTestKeyResolver,
): RetirementEngineFixture {
    val journal =
        StorageBackedMutationJournal<String>(
            storage = storage,
            registrations = mutations.registry.registrations,
            clientId = RETIREMENT_CLIENT_ID,
            hydrateOnFirstUse = true,
        )
    val engine =
        MutationEngine(
            registry = mutations.registry,
            server = backend,
            journal = journal,
            keyResolver = resolver,
            valueCodecVersion = 1,
            valueCodec = FixtureStringArgsCodec,
            baseReader = { key -> handle.read(key) },
            absentAdoption = { key -> handle.recordClear(key) },
            wallClock = clock,
            clientId = RETIREMENT_CLIENT_ID,
        ).also { it.bind(handle) }
    return RetirementEngineFixture(engine, journal)
}

private suspend fun assertLowerSequenceCausalPresentRehomesInterposedRows() {
    val storage = InMemoryMutationJournalStorage()
    val mutations = RetirementMutations()
    val backend = retainingRetirementBackend()
    val handle = RecordingRetirementHandle()
    val clock = TestWallClock(30_000L)
    val source = MutationsTestKey("causal-source")
    val target = MutationsTestKey("causal-target")
    backend.redirectPresent(source, target)
    val engine = openRetirementEngine(storage, mutations, backend, handle, clock)

    val sourcePresent = engine.mutate(source, mutations.set, "causal")
    val executable = engine.mutate(source, mutations.append, "+q")
    val parked = engine.mutate(source, mutations.park, "ignored")
    val targetDelete = engine.mutate(target, mutations.deleteRef, Unit)
    engine.drain(target)

    val deleted = storage.retirementState()
    val sourceSequence = deleted.sequenceOf(sourcePresent)
    val deleteSequence = deleted.sequenceOf(targetDelete)
    assertTrue(sourceSequence < deleteSequence)
    assertEquals(StoredPhase.RETIRED, deleted.executionOf(targetDelete).phase)
    assertActiveTombstone(
        deleted.tombstoneCreatedBy(deleteSequence),
        deleted.executionOf(targetDelete).retiredAt,
    )

    engine.drain(source)

    val completed = storage.retirementState()
    assertEquals(StoredPhase.RETIRED, completed.executionOf(sourcePresent).phase)
    assertEquals(StoredPhase.RETIRED, completed.executionOf(executable).phase)
    assertEquals(StoredPhase.PARKED, completed.executionOf(parked).phase)
    assertSupersededTombstone(
        record = completed.tombstoneCreatedBy(deleteSequence),
        successorSequence = sourceSequence,
        activatedAt = assertNotNull(deleted.tombstoneCreatedBy(deleteSequence).activatedAt),
        supersededAt = assertNotNull(completed.executionOf(sourcePresent).retiredAt),
    )
    assertEquals(target.identity(), engine.terminalIdentityOf(source.identity()))
    assertEquals("causal+q", handle.valueAt(target))
    assertEquals(2L, assertNotNull(completed.client).retiredThroughSequence)
    assertEquals(
        listOf(target.identity(), source.identity(), target.identity()),
        backend.receivedPushes.map { request -> request.key.identity() },
    )
    assertEquals(parked, engine.deadLetters().single().mutationId)
}

private suspend fun assertRetiredTargetPresentCanBeCausallyReplaced() {
    val storage = InMemoryMutationJournalStorage()
    val mutations = RetirementMutations()
    val backend = retainingRetirementBackend()
    val handle = RecordingRetirementHandle()
    val source = MutationsTestKey("present-source")
    val target = MutationsTestKey("present-target")
    backend.redirectPresent(source, target)
    val engine =
        openRetirementEngine(storage, mutations, backend, handle, TestWallClock(31_000L))

    val sourcePresent = engine.mutate(source, mutations.set, "source-authority")
    val targetPresent = engine.mutate(target, mutations.set, "target-authority")
    engine.drain(target)
    assertEquals(StoredPhase.RETIRED, storage.retirementState().executionOf(targetPresent).phase)
    assertEquals("target-authority", handle.valueAt(target))

    engine.drain(source)

    val completed = storage.retirementState()
    assertTrue(completed.sequenceOf(sourcePresent) < completed.sequenceOf(targetPresent))
    assertEquals(StoredPhase.RETIRED, completed.executionOf(sourcePresent).phase)
    assertEquals("source-authority", handle.valueAt(target))
    assertTrue(completed.tombstones.isEmpty())
    assertEquals(
        listOf(target.identity(), source.identity()),
        backend.receivedPushes.map { request -> request.key.identity() },
    )
}

private suspend fun assertLostAckOwnerSurvivesRestart() {
    val storage = FailPointJournalStorage(InMemoryMutationJournalStorage())
    val mutations = RetirementMutations()
    val backend = retainingRetirementBackend().apply { dedupingPushBehavior = true }
    val handle = RecordingRetirementHandle()
    val clock = TestWallClock(32_000L)
    val source = MutationsTestKey("lost-ack-source")
    val target = MutationsTestKey("lost-ack-target")
    val other =
        MutationsTestKey(
            "lost-ack-other",
            StoreNamespace("retirement-other-namespace"),
        )
    backend.redirectPresent(source, target)
    val first =
        openRetirementEngine(
            storage,
            mutations,
            backend,
            handle,
            clock,
            RETIREMENT_ANY_NAMESPACE_RESOLVER,
        )
    val sourceId = first.mutate(source, mutations.set, "source-old")
    val targetId = first.mutate(target, mutations.set, "target-new")
    val otherId = first.mutate(other, mutations.set, "other")
    storage.armKillBeforeCommit(JournalFailPointBoundary.ACK_RECEIPT)

    val death = assertIs<FailPointProcessDeathException>(captureRetirementFailure { first.drain(source) })
    assertFalse(death.committed)
    assertEquals(StoredPhase.INFLIGHT, storage.retirementState().executionOf(sourceId).phase)
    val sourceRequestsBeforeRestart = backend.receivedPushes.count { it.key.identity() == source.identity() }

    val reopened =
        openRetirementEngine(
            storage,
            mutations,
            backend,
            handle,
            clock,
            RETIREMENT_ANY_NAMESPACE_RESOLVER,
        )
    val transportBeforeNonowner = backend.receivedPushes.size
    reopened.drain(target)
    assertEquals(transportBeforeNonowner, backend.receivedPushes.size)
    assertEquals(StoredPhase.UNPREPARED, storage.retirementState().executionOf(targetId).phase)

    reopened.drain(other)
    assertEquals(StoredPhase.RETIRED, storage.retirementState().executionOf(otherId).phase)
    assertEquals(StoredPhase.INFLIGHT, storage.retirementState().executionOf(sourceId).phase)

    reopened.drain(source)
    val completed = storage.retirementState()
    assertEquals(StoredPhase.RETIRED, completed.executionOf(sourceId).phase)
    assertEquals(StoredPhase.RETIRED, completed.executionOf(targetId).phase)
    assertEquals(
        sourceRequestsBeforeRestart + 1,
        backend.receivedPushes.count { it.key.identity() == source.identity() },
    )
    val sourceIdempotencyKey =
        backend.receivedPushes.first { it.key.identity() == source.identity() }.idempotencyKey
    assertEquals(1, backend.effectivePushApplications.count { it == sourceIdempotencyKey })
    assertEquals("target-new", handle.valueAt(target))
}

private suspend fun assertAckedRestartDoesNotRepush() {
    val storage = FailPointJournalStorage(InMemoryMutationJournalStorage())
    val mutations = RetirementMutations()
    val backend = retainingRetirementBackend().apply { dedupingPushBehavior = true }
    val handle = RecordingRetirementHandle()
    val clock = TestWallClock(33_000L)
    val source = MutationsTestKey("acked-source")
    val target = MutationsTestKey("acked-target")
    backend.redirectPresent(source, target)
    val first = openRetirementEngine(storage, mutations, backend, handle, clock)
    val sourceId = first.mutate(source, mutations.set, "source")
    val targetId = first.mutate(target, mutations.set, "target")
    storage.armKillAfterCommit(JournalFailPointBoundary.ACK_RECEIPT)

    val death = assertIs<FailPointProcessDeathException>(captureRetirementFailure { first.drain(source) })
    assertTrue(death.committed)
    assertEquals(StoredPhase.ACKED, storage.retirementState().executionOf(sourceId).phase)

    val reopened = openRetirementEngine(storage, mutations, backend, handle, clock)
    val beforeNonowner = backend.receivedPushes.size
    reopened.drain(target)
    assertEquals(beforeNonowner, backend.receivedPushes.size)
    reopened.drain(source)

    assertEquals(1, backend.receivedPushes.count { it.key.identity() == source.identity() })
    val completed = storage.retirementState()
    assertEquals(StoredPhase.RETIRED, completed.executionOf(sourceId).phase)
    assertEquals(StoredPhase.RETIRED, completed.executionOf(targetId).phase)
}

private suspend fun assertAliasFanInSupersedesEveryCollapsedWatermark() {
    val storage = InMemoryMutationJournalStorage()
    val mutations = RetirementMutations()
    val backend = retainingRetirementBackend()
    val handle = RecordingRetirementHandle()
    val source = MutationsTestKey("fan-in-source")
    val sibling = MutationsTestKey("fan-in-sibling")
    val target = MutationsTestKey("fan-in-target")
    seedAliasFanIn(storage, sibling, target)
    backend.redirectPresent(source, target)
    val engine =
        openRetirementEngine(storage, mutations, backend, handle, TestWallClock(34_000L))

    val mutationId = engine.mutate(source, mutations.set, "fan-in")
    engine.drain(source)

    val completed = storage.retirementState()
    val successor = completed.sequenceOf(mutationId)
    val collapsed =
        completed.tombstones.filter { tombstone ->
            tombstone.namespace == target.namespace.value &&
                tombstone.canonicalId in setOf(sibling.canonicalId(), target.canonicalId())
        }
    assertEquals(2, collapsed.size)
    collapsed.forEach { tombstone ->
        assertEquals(MutationTombstoneState.SUPERSEDED, tombstone.state)
        assertEquals(RETIREMENT_CLIENT_ID, tombstone.supersededByClientId)
        assertEquals(successor, tombstone.supersededBySequence)
        assertNotNull(tombstone.activatedAt)
        assertNotNull(tombstone.supersededAt)
    }
    assertEquals(target.identity(), engine.terminalIdentityOf(source.identity()))
    assertEquals(target.identity(), engine.terminalIdentityOf(sibling.identity()))
}

private suspend fun assertFinalizationKillWindows() {
    val before = prepareLowerSequenceCausalRetirement("kill-before")
    before.storage.armKillBeforeCommit(JournalFailPointBoundary.FINALIZATION)
    val beforeDeath =
        assertIs<FailPointProcessDeathException>(
            captureRetirementFailure { before.engine.drain(before.source) },
        )
    assertFalse(beforeDeath.committed)
    val held = before.storage.retirementState()
    assertEquals(StoredPhase.EFFECTS_PENDING, held.executionOf(before.sourceMutationId).phase)
    assertEquals(MutationAliasState.PENDING, held.aliases.single().state)
    assertActiveTombstone(
        held.tombstoneCreatedBy(held.sequenceOf(before.deleteMutationId)),
        held.executionOf(before.deleteMutationId).retiredAt,
    )
    val sourcePushesBeforeRestart =
        before.backend.receivedPushes.count { it.key.identity() == before.source.identity() }
    val beforeReopened =
        openRetirementEngine(
            before.storage,
            before.mutations,
            before.backend,
            before.handle,
            before.clock,
        )
    beforeReopened.drain(before.source)
    assertEquals(
        sourcePushesBeforeRestart,
        before.backend.receivedPushes.count { it.key.identity() == before.source.identity() },
    )
    assertEquals(
        StoredPhase.RETIRED,
        before.storage.retirementState().executionOf(before.sourceMutationId).phase,
    )

    val after = prepareLowerSequenceCausalRetirement("kill-after")
    after.storage.armKillAfterCommit(JournalFailPointBoundary.FINALIZATION)
    val afterDeath =
        assertIs<FailPointProcessDeathException>(
            captureRetirementFailure { after.engine.drain(after.source) },
        )
    assertTrue(afterDeath.committed)
    val committed = after.storage.retirementState()
    assertEquals(StoredPhase.RETIRED, committed.executionOf(after.sourceMutationId).phase)
    assertEquals(MutationAliasState.ACTIVE, committed.aliases.single().state)
    val afterPushes = after.backend.receivedPushes.size
    val afterReopened =
        openRetirementEngine(
            after.storage,
            after.mutations,
            after.backend,
            after.handle,
            after.clock,
        )
    afterReopened.drain(after.source)
    assertEquals(afterPushes, after.backend.receivedPushes.size)
}

private suspend fun TestScope.assertAliasRetirementPublicationIsCoherent() {
    val storage = FailPointJournalStorage(InMemoryMutationJournalStorage())
    val mutations = RetirementMutations()
    val backend =
        retainingRetirementBackend().apply {
            retireBehavior = {
                throw IllegalStateException("publication checkpoint unavailable")
            }
        }
    val handle = RecordingRetirementHandle()
    val clock = TestWallClock(35_000L)
    val target = MutationsTestKey("publication-target")
    val source = MutationsTestKey("publication-source")
    backend.redirectPresent(source, target)
    val fixture = openRetirementFixture(storage, mutations, backend, handle, clock)
    val deleteId = fixture.engine.mutate(target, mutations.deleteRef, Unit)
    fixture.engine.drain(target)
    val sourceId = fixture.engine.mutate(source, mutations.set, "published")
    val contender = MutationsTestKey("publication-contender")
    val contenderId = fixture.engine.mutate(contender, mutations.set, "queued")
    storage.armFailTransaction(JournalFailPointBoundary.FINALIZATION)
    assertIs<FailPointTransactionException>(
        captureRetirementFailure { fixture.engine.drain(source) },
    )

    fixture.journal.runtimeState.mutex.lock()
    val finalizer = backgroundScope.async { fixture.engine.drain(source) }
    try {
        runCurrent()
        assertTrue(finalizer.isActive)
        val durable = storage.retirementState()
        assertEquals(StoredPhase.RETIRED, durable.executionOf(sourceId).phase)
        assertEquals(MutationAliasState.ACTIVE, durable.aliases.single().state)
        assertEquals(
            MutationTombstoneState.SUPERSEDED,
            durable.tombstoneCreatedBy(durable.sequenceOf(deleteId)).state,
        )

        val runtime = fixture.journal.runtimeSnapshot()
        assertEquals(AliasEdgeState.PENDING, assertNotNull(runtime.aliases[source.identity()]).state)
        assertEquals(
            listOf(sourceId),
            runtime.entries[source.identity()].orEmpty().map { entry -> entry.mutationId },
        )
        assertEquals(
            MutationTombstoneState.ACTIVE,
            fixture.engine.tombstoneSnapshot(target.identity()).single().state,
        )

        val pushesBeforeContender = backend.receivedPushes.size
        val contenderPass = backgroundScope.async { fixture.engine.drain(contender) }
        runCurrent()
        assertTrue(contenderPass.isCompleted)
        contenderPass.await()
        assertEquals(pushesBeforeContender, backend.receivedPushes.size)
        assertEquals(StoredPhase.UNPREPARED, storage.retirementState().executionOf(contenderId).phase)
    } finally {
        fixture.journal.runtimeState.mutex.unlock()
        finalizer.await()
    }

    val published = fixture.journal.runtimeSnapshot()
    assertEquals(AliasEdgeState.ACTIVE, assertNotNull(published.aliases[source.identity()]).state)
    assertTrue(source.identity() !in published.entries)
    assertEquals(
        MutationTombstoneState.SUPERSEDED,
        fixture.engine.tombstoneSnapshot(target.identity()).single().state,
    )
    assertEquals(1, backend.receivedPushes.count { it.key.identity() == source.identity() })

    fixture.engine.drain(contender)
    assertEquals(StoredPhase.RETIRED, storage.retirementState().executionOf(contenderId).phase)
    assertEquals(1, backend.receivedPushes.count { it.key.identity() == contender.identity() })
}

private data class LowerSequenceCausalRetirement(
    val storage: FailPointJournalStorage,
    val mutations: RetirementMutations,
    val backend: FakeBackend,
    val handle: RecordingRetirementHandle,
    val clock: TestWallClock,
    val engine: MutationEngine<MutationsTestKey, String>,
    val source: MutationsTestKey,
    val sourceMutationId: String,
    val deleteMutationId: String,
)

private suspend fun prepareLowerSequenceCausalRetirement(label: String): LowerSequenceCausalRetirement {
    val storage = FailPointJournalStorage(InMemoryMutationJournalStorage())
    val mutations = RetirementMutations()
    val backend = retainingRetirementBackend()
    val handle = RecordingRetirementHandle()
    val clock = TestWallClock(36_000L)
    val source = MutationsTestKey("$label-source")
    val target = MutationsTestKey("$label-target")
    backend.redirectPresent(source, target)
    val engine = openRetirementEngine(storage, mutations, backend, handle, clock)
    val sourceMutationId = engine.mutate(source, mutations.set, "$label-present")
    val deleteMutationId = engine.mutate(target, mutations.deleteRef, Unit)
    engine.drain(target)
    return LowerSequenceCausalRetirement(
        storage,
        mutations,
        backend,
        handle,
        clock,
        engine,
        source,
        sourceMutationId,
        deleteMutationId,
    )
}

private fun retainingRetirementBackend(): FakeBackend =
    FakeBackend().apply {
        retireBehavior = {
            MutationRetirementAck(confirmedThroughSequence = 0L)
        }
    }

private fun FakeBackend.redirectPresent(
    source: MutationsTestKey,
    target: MutationsTestKey,
) {
    pushBehavior = { key, value ->
        MutationPresentAck(
            authoritative = value,
            etag = "retirement-${receivedPushes.size}",
            canonicalKey = target.takeIf { key.identity() == source.identity() },
        )
    }
}

private val RETIREMENT_ANY_NAMESPACE_RESOLVER =
    MutationKeyResolver<MutationsTestKey> { identity ->
        MutationsTestKey(identity.canonicalId, StoreNamespace(identity.namespace))
    }

private suspend fun seedAliasFanIn(
    storage: MutationJournalStorage,
    sibling: MutationsTestKey,
    target: MutationsTestKey,
) {
    storage.transaction { transaction ->
        insertActiveAlias(transaction, sibling, target, sequence = 91L)
        insertActiveForeignTombstone(transaction, sibling, "fan-in-sibling-client", 7L, 700L)
        insertActiveForeignTombstone(transaction, target, "fan-in-target-client", 8L, 800L)
    }
}

private fun insertActiveForeignTombstone(
    transaction: MutationJournalTransaction,
    key: MutationsTestKey,
    clientId: String,
    sequence: Long,
    activatedAt: Long,
) {
    val pending =
        MutationKeyTombstoneRecord(
            namespace = key.namespace.value,
            canonicalId = key.canonicalId(),
            createdByClientId = clientId,
            createdBySequence = sequence,
            state = MutationTombstoneState.PENDING,
            createdAt = activatedAt - 1L,
            activatedAt = null,
            supersededByClientId = null,
            supersededBySequence = null,
            supersededAt = null,
        )
    transaction.insertTombstone(pending)
    transaction.advanceTombstone(
        MutationKeyTombstoneRecord(
            namespace = pending.namespace,
            canonicalId = pending.canonicalId,
            createdByClientId = pending.createdByClientId,
            createdBySequence = pending.createdBySequence,
            state = MutationTombstoneState.ACTIVE,
            createdAt = pending.createdAt,
            activatedAt = activatedAt,
            supersededByClientId = null,
            supersededBySequence = null,
            supersededAt = null,
        ),
    )
}

private data class RetirementDurableState(
    val client: MutationClientRecord?,
    val intents: List<MutationIntentRecord>,
    val executions: List<MutationExecutionRecord>,
    val aliases: List<MutationKeyAliasRecord>,
    val tombstones: List<MutationKeyTombstoneRecord>,
) {
    fun sequenceOf(mutationId: String): Long =
        intents.single { it.mutationId == mutationId }.clientSequence

    fun executionOf(mutationId: String): MutationExecutionRecord {
        val sequence = sequenceOf(mutationId)
        return executions.single { it.clientSequence == sequence }
    }

    fun tombstoneCreatedBy(sequence: Long): MutationKeyTombstoneRecord =
        tombstones.single {
            it.createdByClientId == RETIREMENT_CLIENT_ID && it.createdBySequence == sequence
        }

    fun accountingFor(vararg mutationIds: String): RetirementAccounting =
        RetirementAccounting(
            retiredThroughSequence = assertNotNull(client).retiredThroughSequence,
            executions =
                mutationIds.associateWith { mutationId ->
                    val execution = executionOf(mutationId)
                    ExecutionAccounting(
                        phase = execution.phase,
                        generation = execution.currentGeneration,
                        retired = execution.retiredAt != null,
                    )
                },
        )
}

private data class RetirementAccounting(
    val retiredThroughSequence: Long,
    val executions: Map<String, ExecutionAccounting>,
)

private data class ExecutionAccounting(
    val phase: StoredPhase,
    val generation: Int,
    val retired: Boolean,
)

private enum class CheckpointGap(
    val phase: StoredPhase,
) {
    PARKED(StoredPhase.PARKED),
    ACTIVE(StoredPhase.UNPREPARED),
    EFFECTS_PENDING(StoredPhase.EFFECTS_PENDING),
}

private suspend fun seedCheckpointGap(
    storage: MutationJournalStorage,
    gap: CheckpointGap,
) {
    storage.transaction { transaction ->
        transaction.insertClient(
            MutationClientRecord(1, RETIREMENT_CLIENT_ID, 0L, 0L, 0L, 100L),
        )
        transaction.advanceClient(
            MutationClientRecord(1, RETIREMENT_CLIENT_ID, 3L, 0L, 0L, 100L),
        )
        insertCheckpointIntent(transaction, sequence = 1L, namespace = "checkpoint-retired-1")
        insertCheckpointIntent(transaction, sequence = 2L, namespace = "checkpoint-gap")
        insertCheckpointIntent(transaction, sequence = 3L, namespace = "checkpoint-retired-3")
        retireCheckpointIntent(transaction, sequence = 1L, namespace = "checkpoint-retired-1")
        when (gap) {
            CheckpointGap.PARKED -> {
                val failure =
                    transaction.appendFailure(
                        clientId = RETIREMENT_CLIENT_ID,
                        clientSequence = 2L,
                        generation = 0,
                        kind = MutationFailureKind.CODEC,
                        detail = "checkpoint-gap",
                        message = "parked checkpoint gap",
                        occurredAt = 250L,
                    )
                transaction.advanceExecution(
                    MutationExecutionRecord(
                        clientId = RETIREMENT_CLIENT_ID,
                        clientSequence = 2L,
                        phase = StoredPhase.PARKED,
                        currentGeneration = 0,
                        attempt = 0,
                        lastAttemptAt = null,
                        activeFailureId = failure.failureId,
                        retiredAt = null,
                    ),
                )
            }
            CheckpointGap.ACTIVE -> Unit
            CheckpointGap.EFFECTS_PENDING ->
                acknowledgeCheckpointIntent(
                    transaction,
                    sequence = 2L,
                    namespace = "checkpoint-gap",
                )
        }
        retireCheckpointIntent(transaction, sequence = 3L, namespace = "checkpoint-retired-3")
        transaction.advanceClient(
            MutationClientRecord(1, RETIREMENT_CLIENT_ID, 3L, 1L, 0L, 100L),
        )
    }
}

private fun insertCheckpointIntent(
    transaction: MutationJournalTransaction,
    sequence: Long,
    namespace: String,
) {
    transaction.insertIntent(
        recordVersion = 1,
        clientId = RETIREMENT_CLIENT_ID,
        clientSequence = sequence,
        mutationId = "checkpoint-mutation-$sequence",
        namespace = namespace,
        canonicalId = "checkpoint-$sequence",
        mutatorId = "retirement-set",
        mutatorVersion = 1,
        argsBlob = "value-$sequence".encodeToByteArray(),
        idempotencyRoot = "$RETIREMENT_CLIENT_ID:$sequence",
        createdAt = 100L + sequence,
    )
    transaction.insertExecution(
        MutationExecutionRecord(
            clientId = RETIREMENT_CLIENT_ID,
            clientSequence = sequence,
            phase = StoredPhase.UNPREPARED,
            currentGeneration = 0,
            attempt = 0,
            lastAttemptAt = null,
            activeFailureId = null,
            retiredAt = null,
        ),
    )
}

private fun acknowledgeCheckpointIntent(
    transaction: MutationJournalTransaction,
    sequence: Long,
    namespace: String,
) {
    transaction.insertAttempt(
        MutationAttemptRecord(
            clientId = RETIREMENT_CLIENT_ID,
            clientSequence = sequence,
            generation = 1,
            effectiveNamespace = namespace,
            effectiveCanonicalId = "checkpoint-$sequence",
            valueCodecVersion = 1,
            basePresence = MutationPresenceState.PRESENT,
            baseBlob = "base".encodeToByteArray(),
            minePresence = MutationPresenceState.PRESENT,
            mineBlob = "value-$sequence".encodeToByteArray(),
            preconditionMetaPresent = false,
            preconditionWrittenAt = null,
            preconditionEtag = null,
            advertisedRetiredThroughSequence = 0L,
            generationIdempotencyKey = "$RETIREMENT_CLIENT_ID:$sequence:g1",
            preparedAt = 200L + sequence,
            conflictMetaPresent = null,
            conflictWrittenAt = null,
            conflictEtag = null,
            conflictReceivedAt = null,
        ),
    )
    transaction.advanceExecution(seedExecution(sequence, StoredPhase.READY, attempt = 0))
    transaction.advanceExecution(seedExecution(sequence, StoredPhase.INFLIGHT, attempt = 0))
    transaction.insertAck(
        MutationAckRecord(
            clientId = RETIREMENT_CLIENT_ID,
            clientSequence = sequence,
            generation = 1,
            authoritativePresence = MutationPresenceState.PRESENT,
            authoritativeBlob = "value-$sequence".encodeToByteArray(),
            valueCodecVersion = 1,
            etag = "checkpoint-$sequence",
            canonicalTargetNamespace = null,
            canonicalTargetId = null,
            receivedAt = 300L + sequence,
        ),
    )
    transaction.advanceExecution(
        seedExecution(sequence, StoredPhase.ACKED, attempt = 1, time = 300L + sequence),
    )
    transaction.advanceExecution(
        seedExecution(sequence, StoredPhase.EFFECTS_PENDING, attempt = 1, time = 300L + sequence),
    )
}

private fun retireCheckpointIntent(
    transaction: MutationJournalTransaction,
    sequence: Long,
    namespace: String,
) {
    acknowledgeCheckpointIntent(transaction, sequence, namespace)
    transaction.advanceExecution(
        seedExecution(
            sequence = sequence,
            phase = StoredPhase.RETIRED,
            attempt = 1,
            time = 300L + sequence,
            retiredAt = 400L + sequence,
        ),
    )
}

private suspend fun MutationJournalStorage.retirementState(): RetirementDurableState =
    transaction { transaction ->
        RetirementDurableState(
            client = transaction.client(RETIREMENT_CLIENT_ID),
            intents = transaction.intents(RETIREMENT_CLIENT_ID),
            executions = transaction.executions(RETIREMENT_CLIENT_ID),
            aliases = transaction.aliases(),
            tombstones = transaction.tombstones(),
        )
    }

private fun assertPendingTombstone(record: MutationKeyTombstoneRecord) {
    assertEquals(MutationTombstoneState.PENDING, record.state)
    assertNull(record.activatedAt)
    assertNull(record.supersededByClientId)
    assertNull(record.supersededBySequence)
    assertNull(record.supersededAt)
}

private fun assertActiveTombstone(
    record: MutationKeyTombstoneRecord,
    activatedAt: Long?,
) {
    assertEquals(MutationTombstoneState.ACTIVE, record.state)
    assertEquals(assertNotNull(activatedAt), record.activatedAt)
    assertNull(record.supersededByClientId)
    assertNull(record.supersededBySequence)
    assertNull(record.supersededAt)
}

private fun assertSupersededTombstone(
    record: MutationKeyTombstoneRecord,
    successorSequence: Long,
    activatedAt: Long,
    supersededAt: Long,
) {
    assertEquals(MutationTombstoneState.SUPERSEDED, record.state)
    assertEquals(activatedAt, record.activatedAt)
    assertEquals(RETIREMENT_CLIENT_ID, record.supersededByClientId)
    assertEquals(successorSequence, record.supersededBySequence)
    assertEquals(supersededAt, record.supersededAt)
}

private suspend fun seedTombstoneReplay(
    storage: MutationJournalStorage,
    mutations: RetirementMutations,
    source: MutationsTestKey,
    middle: MutationsTestKey,
    canonical: MutationsTestKey,
) {
    storage.transaction { transaction ->
        transaction.insertClient(
            MutationClientRecord(1, RETIREMENT_CLIENT_ID, 0L, 0L, 0L, 100L),
        )
        transaction.advanceClient(
            MutationClientRecord(1, RETIREMENT_CLIENT_ID, 3L, 0L, 0L, 100L),
        )
        insertReplayIntent(
            transaction = transaction,
            sequence = 1L,
            mutationId = PRE_TOMBSTONE_MUTATION_ID,
            key = source,
            mutatorId = mutations.append.id,
            argsBlob = "+stale".encodeToByteArray(),
        )
        insertReplayIntent(
            transaction = transaction,
            sequence = 2L,
            mutationId = TOMBSTONE_MUTATION_ID,
            key = canonical,
            mutatorId = mutations.deleteRef.id,
            argsBlob = ByteArray(0),
        )
        insertReplayIntent(
            transaction = transaction,
            sequence = 3L,
            mutationId = POST_TOMBSTONE_MUTATION_ID,
            key = middle,
            mutatorId = mutations.append.id,
            argsBlob = "+fresh".encodeToByteArray(),
        )
        retireSeedDelete(transaction, canonical)
        insertActiveAlias(transaction, source, middle, sequence = 11L)
        insertActiveAlias(transaction, middle, canonical, sequence = 12L)
    }
}

private fun insertReplayIntent(
    transaction: MutationJournalTransaction,
    sequence: Long,
    mutationId: String,
    key: MutationsTestKey,
    mutatorId: String,
    argsBlob: ByteArray,
) {
    transaction.insertIntent(
        recordVersion = 1,
        clientId = RETIREMENT_CLIENT_ID,
        clientSequence = sequence,
        mutationId = mutationId,
        namespace = key.namespace.value,
        canonicalId = key.canonicalId(),
        mutatorId = mutatorId,
        mutatorVersion = 1,
        argsBlob = argsBlob,
        idempotencyRoot = "$RETIREMENT_CLIENT_ID:$sequence",
        createdAt = 100L + sequence,
    )
    transaction.insertExecution(
        MutationExecutionRecord(
            clientId = RETIREMENT_CLIENT_ID,
            clientSequence = sequence,
            phase = StoredPhase.UNPREPARED,
            currentGeneration = 0,
            attempt = 0,
            lastAttemptAt = null,
            activeFailureId = null,
            retiredAt = null,
        ),
    )
}

private fun retireSeedDelete(
    transaction: MutationJournalTransaction,
    canonical: MutationsTestKey,
) {
    val sequence = 2L
    transaction.insertAttempt(
        MutationAttemptRecord(
            clientId = RETIREMENT_CLIENT_ID,
            clientSequence = sequence,
            generation = 1,
            effectiveNamespace = canonical.namespace.value,
            effectiveCanonicalId = canonical.canonicalId(),
            valueCodecVersion = 1,
            basePresence = MutationPresenceState.PRESENT,
            baseBlob = "base".encodeToByteArray(),
            minePresence = MutationPresenceState.ABSENT,
            mineBlob = null,
            preconditionMetaPresent = false,
            preconditionWrittenAt = null,
            preconditionEtag = null,
            advertisedRetiredThroughSequence = 0L,
            generationIdempotencyKey = "$RETIREMENT_CLIENT_ID:$sequence:g1",
            preparedAt = 200L,
            conflictMetaPresent = null,
            conflictWrittenAt = null,
            conflictEtag = null,
            conflictReceivedAt = null,
        ),
    )
    transaction.advanceExecution(seedExecution(sequence, StoredPhase.READY, attempt = 0))
    transaction.advanceExecution(seedExecution(sequence, StoredPhase.INFLIGHT, attempt = 0))
    transaction.insertAck(
        MutationAckRecord(
            clientId = RETIREMENT_CLIENT_ID,
            clientSequence = sequence,
            generation = 1,
            authoritativePresence = MutationPresenceState.ABSENT,
            authoritativeBlob = null,
            valueCodecVersion = 1,
            etag = "deleted",
            canonicalTargetNamespace = null,
            canonicalTargetId = null,
            receivedAt = 300L,
        ),
    )
    val pending =
        MutationKeyTombstoneRecord(
            namespace = canonical.namespace.value,
            canonicalId = canonical.canonicalId(),
            createdByClientId = RETIREMENT_CLIENT_ID,
            createdBySequence = sequence,
            state = MutationTombstoneState.PENDING,
            createdAt = 300L,
            activatedAt = null,
            supersededByClientId = null,
            supersededBySequence = null,
            supersededAt = null,
        )
    transaction.insertTombstone(pending)
    transaction.advanceExecution(seedExecution(sequence, StoredPhase.ACKED, attempt = 1, time = 300L))
    transaction.advanceExecution(
        seedExecution(sequence, StoredPhase.EFFECTS_PENDING, attempt = 1, time = 300L),
    )
    transaction.advanceTombstone(
        MutationKeyTombstoneRecord(
            namespace = pending.namespace,
            canonicalId = pending.canonicalId,
            createdByClientId = pending.createdByClientId,
            createdBySequence = pending.createdBySequence,
            state = MutationTombstoneState.ACTIVE,
            createdAt = pending.createdAt,
            activatedAt = 400L,
            supersededByClientId = null,
            supersededBySequence = null,
            supersededAt = null,
        ),
    )
    transaction.advanceExecution(
        seedExecution(sequence, StoredPhase.RETIRED, attempt = 1, time = 300L, retiredAt = 400L),
    )
}

private fun seedExecution(
    sequence: Long,
    phase: StoredPhase,
    attempt: Int,
    time: Long? = null,
    retiredAt: Long? = null,
): MutationExecutionRecord =
    MutationExecutionRecord(
        clientId = RETIREMENT_CLIENT_ID,
        clientSequence = sequence,
        phase = phase,
        currentGeneration = 1,
        attempt = attempt,
        lastAttemptAt = time,
        activeFailureId = null,
        retiredAt = retiredAt,
    )

private fun insertActiveAlias(
    transaction: MutationJournalTransaction,
    source: MutationsTestKey,
    target: MutationsTestKey,
    sequence: Long,
) {
    val pending =
        MutationKeyAliasRecord(
            sourceNamespace = source.namespace.value,
            sourceCanonicalId = source.canonicalId(),
            targetNamespace = target.namespace.value,
            targetCanonicalId = target.canonicalId(),
            state = MutationAliasState.PENDING,
            createdByClientId = "alias-client",
            createdBySequence = sequence,
            createdAt = 500L + sequence,
            activatedAt = null,
        )
    transaction.insertAlias(pending)
    transaction.advanceAlias(
        MutationKeyAliasRecord(
            sourceNamespace = pending.sourceNamespace,
            sourceCanonicalId = pending.sourceCanonicalId,
            targetNamespace = pending.targetNamespace,
            targetCanonicalId = pending.targetCanonicalId,
            state = MutationAliasState.ACTIVE,
            createdByClientId = pending.createdByClientId,
            createdBySequence = pending.createdBySequence,
            createdAt = pending.createdAt,
            activatedAt = 600L + sequence,
        ),
    )
}

private suspend fun captureRetirementFailure(block: suspend () -> Unit): Throwable? =
    try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }

private suspend fun expectRetirement(
    errors: MutableList<String>,
    label: String,
    assertion: suspend () -> Unit,
) {
    try {
        assertion()
    } catch (failure: Throwable) {
        errors += "$label: ${failure.message}"
    }
}

private const val RETIREMENT_CLIENT_ID = "client-0"
private const val PRE_TOMBSTONE_MUTATION_ID = "pre-tombstone-replay"
private const val TOMBSTONE_MUTATION_ID = "active-delete"
private const val POST_TOMBSTONE_MUTATION_ID = "post-tombstone-replay"

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
