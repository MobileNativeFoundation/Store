@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.StoreResults
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationFailureRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.testing.TestWallClock

class MutationDrainResumabilityMatrixTest {
    @Test
    fun killAtEveryAwaitPoint_thenRestartDrain_completesWithoutLossOrDoublePush() = runTest {
        assertEquals(38, MATRIX_CELLS.size)
        MATRIX_CELLS.forEachIndexed { cellIndex, cell ->
            val context =
                "masterSeed=$MATRIX_MASTER_SEED cellIndex=$cellIndex cell=${cell.name} " +
                    "transition=${cell.transition} boundary=${cell.boundary} " +
                    "occurrence=${cell.occurrence}"
            try {
                assertMatrixCell(cell)
            } catch (failure: Throwable) {
                throw AssertionError("$context\n${failure.stackTraceToString()}")
            }
        }
    }
}

private enum class MatrixScenario {
    SUCCESS,
    TRANSPORT_ACK_LOST,
    CONFLICT_RECEIPT,
    RETRY_GENERATION,
    SERVER_WINS,
    CHECKPOINT_OR_PRUNE,
    PARK_IDENTITY,
    PARK_CODEC,
    PARK_SELECTOR,
    PARK_MERGE,
    PARK_UNCHANGED_BOUND,
    PARK_PROTOCOL,
}

private enum class MatrixKillMode {
    BEFORE_COMMIT,
    AFTER_COMMIT,
    TRANSPORT,
}

private enum class MatrixTransition {
    PREPARATION,
    INFLIGHT,
    CONFLICT_RECEIPT,
    RETRY_GENERATION,
    SERVER_WINS,
    PARK,
}

private data class MatrixCell(
    val name: String,
    val scenario: MatrixScenario,
    val mode: MatrixKillMode,
    val transition: MatrixTransition? = null,
    val boundary: JournalFailPoint? = null,
    val occurrence: Int = 1,
)

private val MATRIX_CELLS =
    listOf(
        MatrixCell(
            "preparation-before",
            MatrixScenario.SUCCESS,
            MatrixKillMode.BEFORE_COMMIT,
            transition = MatrixTransition.PREPARATION,
        ),
        MatrixCell(
            "preparation-after",
            MatrixScenario.SUCCESS,
            MatrixKillMode.AFTER_COMMIT,
            transition = MatrixTransition.PREPARATION,
        ),
        MatrixCell(
            "inflight-before",
            MatrixScenario.SUCCESS,
            MatrixKillMode.BEFORE_COMMIT,
            transition = MatrixTransition.INFLIGHT,
        ),
        MatrixCell(
            "inflight-after",
            MatrixScenario.SUCCESS,
            MatrixKillMode.AFTER_COMMIT,
            transition = MatrixTransition.INFLIGHT,
        ),
        MatrixCell(
            "transport-acknowledgement-lost",
            MatrixScenario.TRANSPORT_ACK_LOST,
            MatrixKillMode.TRANSPORT,
        ),
        MatrixCell(
            "conflict-receipt-before",
            MatrixScenario.CONFLICT_RECEIPT,
            MatrixKillMode.BEFORE_COMMIT,
            transition = MatrixTransition.CONFLICT_RECEIPT,
        ),
        MatrixCell(
            "conflict-receipt-after",
            MatrixScenario.CONFLICT_RECEIPT,
            MatrixKillMode.AFTER_COMMIT,
            transition = MatrixTransition.CONFLICT_RECEIPT,
        ),
        MatrixCell(
            "generation-g-plus-one-before",
            MatrixScenario.RETRY_GENERATION,
            MatrixKillMode.BEFORE_COMMIT,
            transition = MatrixTransition.RETRY_GENERATION,
        ),
        MatrixCell(
            "generation-g-plus-one-after",
            MatrixScenario.RETRY_GENERATION,
            MatrixKillMode.AFTER_COMMIT,
            transition = MatrixTransition.RETRY_GENERATION,
        ),
        MatrixCell(
            "server-wins-before",
            MatrixScenario.SERVER_WINS,
            MatrixKillMode.BEFORE_COMMIT,
            transition = MatrixTransition.SERVER_WINS,
        ),
        MatrixCell(
            "server-wins-after",
            MatrixScenario.SERVER_WINS,
            MatrixKillMode.AFTER_COMMIT,
            transition = MatrixTransition.SERVER_WINS,
        ),
        MatrixCell(
            "ack-receipt-before",
            MatrixScenario.SUCCESS,
            MatrixKillMode.BEFORE_COMMIT,
            boundary = JournalFailPointBoundary.ACK_RECEIPT,
        ),
        MatrixCell(
            "ack-receipt-after",
            MatrixScenario.SUCCESS,
            MatrixKillMode.AFTER_COMMIT,
            boundary = JournalFailPointBoundary.ACK_RECEIPT,
        ),
        MatrixCell(
            "adoption-before",
            MatrixScenario.SUCCESS,
            MatrixKillMode.BEFORE_COMMIT,
            boundary = JournalFailPointBoundary.ADOPTION_ADVANCE,
        ),
        MatrixCell(
            "adoption-after",
            MatrixScenario.SUCCESS,
            MatrixKillMode.AFTER_COMMIT,
            boundary = JournalFailPointBoundary.ADOPTION_ADVANCE,
        ),
        MatrixCell(
            "effect-zero-before",
            MatrixScenario.SUCCESS,
            MatrixKillMode.BEFORE_COMMIT,
            boundary = JournalFailPointBoundary.EFFECT_MARKER,
            occurrence = 1,
        ),
        MatrixCell(
            "effect-zero-after",
            MatrixScenario.SUCCESS,
            MatrixKillMode.AFTER_COMMIT,
            boundary = JournalFailPointBoundary.EFFECT_MARKER,
            occurrence = 1,
        ),
        MatrixCell(
            "effect-one-before",
            MatrixScenario.SUCCESS,
            MatrixKillMode.BEFORE_COMMIT,
            boundary = JournalFailPointBoundary.EFFECT_MARKER,
            occurrence = 2,
        ),
        MatrixCell(
            "effect-one-after",
            MatrixScenario.SUCCESS,
            MatrixKillMode.AFTER_COMMIT,
            boundary = JournalFailPointBoundary.EFFECT_MARKER,
            occurrence = 2,
        ),
        MatrixCell(
            "finalization-before",
            MatrixScenario.SUCCESS,
            MatrixKillMode.BEFORE_COMMIT,
            boundary = JournalFailPointBoundary.FINALIZATION,
        ),
        MatrixCell(
            "finalization-after",
            MatrixScenario.SUCCESS,
            MatrixKillMode.AFTER_COMMIT,
            boundary = JournalFailPointBoundary.FINALIZATION,
        ),
        MatrixCell(
            "checkpoint-confirmation-before",
            MatrixScenario.CHECKPOINT_OR_PRUNE,
            MatrixKillMode.BEFORE_COMMIT,
            boundary = JournalFailPointBoundary.CHECKPOINT_CONFIRMATION,
        ),
        MatrixCell(
            "checkpoint-confirmation-after",
            MatrixScenario.CHECKPOINT_OR_PRUNE,
            MatrixKillMode.AFTER_COMMIT,
            boundary = JournalFailPointBoundary.CHECKPOINT_CONFIRMATION,
        ),
        MatrixCell(
            "before-prune",
            MatrixScenario.CHECKPOINT_OR_PRUNE,
            MatrixKillMode.BEFORE_COMMIT,
            boundary = JournalFailPointBoundary.BEFORE_PRUNE,
        ),
        MatrixCell(
            "before-prune-commit",
            MatrixScenario.CHECKPOINT_OR_PRUNE,
            MatrixKillMode.BEFORE_COMMIT,
            boundary = JournalFailPointBoundary.PRUNE_COMMIT,
        ),
        MatrixCell(
            "after-prune-commit",
            MatrixScenario.CHECKPOINT_OR_PRUNE,
            MatrixKillMode.AFTER_COMMIT,
            boundary = JournalFailPointBoundary.PRUNE_COMMIT,
        ),
        parkCell("identity-park-before", MatrixScenario.PARK_IDENTITY, MatrixKillMode.BEFORE_COMMIT),
        parkCell("identity-park-after", MatrixScenario.PARK_IDENTITY, MatrixKillMode.AFTER_COMMIT),
        parkCell("codec-park-before", MatrixScenario.PARK_CODEC, MatrixKillMode.BEFORE_COMMIT),
        parkCell("codec-park-after", MatrixScenario.PARK_CODEC, MatrixKillMode.AFTER_COMMIT),
        parkCell("selector-park-before", MatrixScenario.PARK_SELECTOR, MatrixKillMode.BEFORE_COMMIT),
        parkCell("selector-park-after", MatrixScenario.PARK_SELECTOR, MatrixKillMode.AFTER_COMMIT),
        parkCell("merge-park-before", MatrixScenario.PARK_MERGE, MatrixKillMode.BEFORE_COMMIT),
        parkCell("merge-park-after", MatrixScenario.PARK_MERGE, MatrixKillMode.AFTER_COMMIT),
        parkCell(
            "unchanged-bound-park-before",
            MatrixScenario.PARK_UNCHANGED_BOUND,
            MatrixKillMode.BEFORE_COMMIT,
        ),
        parkCell(
            "unchanged-bound-park-after",
            MatrixScenario.PARK_UNCHANGED_BOUND,
            MatrixKillMode.AFTER_COMMIT,
        ),
        parkCell("protocol-park-before", MatrixScenario.PARK_PROTOCOL, MatrixKillMode.BEFORE_COMMIT),
        parkCell("protocol-park-after", MatrixScenario.PARK_PROTOCOL, MatrixKillMode.AFTER_COMMIT),
    )

private fun parkCell(
    name: String,
    scenario: MatrixScenario,
    mode: MatrixKillMode,
): MatrixCell = MatrixCell(name, scenario, mode, transition = MatrixTransition.PARK)

private suspend fun assertMatrixCell(cell: MatrixCell) {
    when (cell.scenario) {
        MatrixScenario.SUCCESS -> assertSuccessfulLifecycleCell(cell)
        MatrixScenario.TRANSPORT_ACK_LOST -> assertTransportAcknowledgementLostCell()
        MatrixScenario.CONFLICT_RECEIPT,
        MatrixScenario.RETRY_GENERATION,
        -> assertConflictRetryCell(cell)
        MatrixScenario.SERVER_WINS -> assertServerWinsCell(cell)
        MatrixScenario.CHECKPOINT_OR_PRUNE -> assertCheckpointOrPruneCell(cell)
        MatrixScenario.PARK_IDENTITY -> assertIdentityParkCell(cell)
        MatrixScenario.PARK_CODEC -> assertCodecParkCell(cell)
        MatrixScenario.PARK_SELECTOR -> assertSelectorParkCell(cell)
        MatrixScenario.PARK_MERGE -> assertMergeParkCell(cell)
        MatrixScenario.PARK_UNCHANGED_BOUND -> assertUnchangedBoundParkCell(cell)
        MatrixScenario.PARK_PROTOCOL -> assertProtocolParkCell(cell)
    }
}

private suspend fun assertSuccessfulLifecycleCell(cell: MatrixCell) {
    val raw = InMemoryMutationJournalStorage()
    val storage = FailPointJournalStorage(raw)
    val mutations = MatrixMutations(effectful = true)
    val backend = retainingMatrixBackend()
    val state = MatrixState()
    val clock = TestWallClock(10_000L)
    val key = MutationsTestKey(cell.name, MATRIX_NAMESPACE)
    val first = openMatrixEngine(storage, mutations.registry, backend, state, clock)
    val mutationId = first.mutate(key, mutations.set, "value-${cell.name}")
    storage.arm(cell)

    assertJournalDeath(cell, storage) { first.drain(key) }

    val reopened =
        openMatrixEngine(
            FailPointJournalStorage(raw),
            mutations.registry,
            backend,
            state,
            clock,
        )
    reopened.drain(key)

    assertRetired(raw, mutationId, reopened)
    assertEquals(1, backend.effectivePushApplications.size)
    assertEveryEffectivePushAppliedOnce(backend)
    val expectedReceivedPushes =
        if (
            cell.boundary == JournalFailPointBoundary.ACK_RECEIPT &&
            cell.mode == MatrixKillMode.BEFORE_COMMIT
        ) {
            2
        } else {
            1
        }
    assertPushHighWater(backend, mutationId, expectedReceivedPushes, expectedDistinctKeys = 1)
    assertTrue(MATRIX_EFFECT_KEY.identity() in state.staleKeys)
    assertTrue(state.staleNamespaces.any { it.value == MATRIX_EFFECT_NAMESPACE.value })
}

private suspend fun assertTransportAcknowledgementLostCell() {
    val raw = InMemoryMutationJournalStorage()
    val mutations = MatrixMutations(effectful = false)
    val backend = retainingMatrixBackend()
    val state = MatrixState()
    val clock = TestWallClock(20_000L)
    val key = MutationsTestKey("transport-ack-lost", MATRIX_NAMESPACE)
    val first =
        openMatrixEngine(
            FailPointJournalStorage(raw),
            mutations.registry,
            MatrixAckLossServer(backend),
            state,
            clock,
        )
    val mutationId = first.mutate(key, mutations.set, "transport-value")

    assertIs<MatrixTransportAckLostException>(captureMatrixFailure { first.drain(key) })
    assertEquals(StoredPhase.INFLIGHT, raw.matrixState(mutationId).execution.phase)

    val reopened =
        openMatrixEngine(
            FailPointJournalStorage(raw),
            mutations.registry,
            backend,
            state,
            clock,
        )
    reopened.drain(key)

    assertRetired(raw, mutationId, reopened)
    assertPushHighWater(backend, mutationId, expectedReceipts = 2, expectedDistinctKeys = 1)
    assertEquals(1, backend.effectivePushApplications.size)
    assertEveryEffectivePushAppliedOnce(backend)
}

private suspend fun assertConflictRetryCell(cell: MatrixCell) {
    val raw = InMemoryMutationJournalStorage()
    val storage = FailPointJournalStorage(raw)
    val mutations = MatrixMutations(effectful = false)
    val backend = retainingMatrixBackend()
    backend.pushBehavior = { _, value ->
        if (backend.receivedPushes.last().generation == 1) {
            throw matrixConflict(MatrixMeta(1L, "same-conflict"))
        }
        MutationPresentAck(value, "retry-ack", null)
    }
    val state = MatrixState()
    val clock = TestWallClock(30_000L)
    val key = MutationsTestKey(cell.name, MATRIX_NAMESPACE)
    val policy =
        MatrixConflictPolicy(
            merge = { _, mine, _ -> MutationConflictResolution.Retry(mine) },
        )
    val first = openMatrixEngine(storage, mutations.registry, backend, state, clock, conflicts = policy)
    val mutationId = first.mutate(key, mutations.set, "conflict-value")

    if (cell.scenario == MatrixScenario.RETRY_GENERATION) {
        first.drain(key)
        assertEquals(StoredPhase.REFRESH_REQUIRED, raw.matrixState(mutationId).execution.phase)
        clock.setEpochMillis(32_000L)
    }
    storage.arm(cell)
    assertJournalDeath(cell, storage) { first.drain(key) }

    if (
        cell.scenario == MatrixScenario.CONFLICT_RECEIPT &&
        cell.mode == MatrixKillMode.AFTER_COMMIT
    ) {
        clock.setEpochMillis(32_000L)
    }
    val reopened =
        openMatrixEngine(
            FailPointJournalStorage(raw),
            mutations.registry,
            backend,
            state,
            clock,
            conflicts = policy,
        )
    reopened.drain(key)
    if (
        cell.scenario == MatrixScenario.CONFLICT_RECEIPT &&
        cell.mode == MatrixKillMode.BEFORE_COMMIT
    ) {
        clock.setEpochMillis(34_000L)
        reopened.drain(key)
    }

    val completed = assertRetired(raw, mutationId, reopened)
    assertEquals(listOf(1, 2), completed.attempts.map { it.generation })
    assertEquals(1, backend.effectivePushApplications.size)
    assertEveryEffectivePushAppliedOnce(backend)
    val receivedByGeneration = backend.receivedPushes.groupingBy { it.generation }.eachCount()
    val generationOneReceipts =
        if (
            cell.scenario == MatrixScenario.CONFLICT_RECEIPT &&
            cell.mode == MatrixKillMode.BEFORE_COMMIT
        ) {
            2
        } else {
            1
        }
    assertEquals(
        generationOneReceipts,
        receivedByGeneration.getValue(1),
    )
    assertEquals(1, receivedByGeneration.getValue(2))
    assertPushHighWater(
        backend,
        mutationId,
        expectedReceipts = generationOneReceipts + 1,
        expectedDistinctKeys = 2,
    )
}

private suspend fun assertServerWinsCell(cell: MatrixCell) {
    val raw = InMemoryMutationJournalStorage()
    val storage = FailPointJournalStorage(raw)
    val mutations = MatrixMutations(effectful = false)
    val backend = retainingMatrixBackend()
    backend.pushBehavior = { _, _ -> throw matrixConflict(MatrixMeta(2L, "server-wins")) }
    val state = MatrixState()
    val clock = TestWallClock(40_000L)
    val key = MutationsTestKey(cell.name, MATRIX_NAMESPACE)
    val policy =
        MatrixConflictPolicy(
            merge = { _, _, _ -> MutationConflictResolution.ServerWins },
        )
    val first = openMatrixEngine(storage, mutations.registry, backend, state, clock, conflicts = policy)
    val mutationId = first.mutate(key, mutations.set, "server-wins-value")
    first.drain(key)
    assertEquals(StoredPhase.REFRESH_REQUIRED, raw.matrixState(mutationId).execution.phase)
    clock.setEpochMillis(42_000L)
    storage.arm(cell)

    assertJournalDeath(cell, storage) { first.drain(key) }

    val reopened =
        openMatrixEngine(
            FailPointJournalStorage(raw),
            mutations.registry,
            backend,
            state,
            clock,
            conflicts = policy,
        )
    reopened.drain(key)

    assertRetired(raw, mutationId, reopened)
    assertPushHighWater(backend, mutationId, expectedReceipts = 1, expectedDistinctKeys = 1)
    assertTrue(backend.effectivePushApplications.isEmpty())
}

private suspend fun assertCheckpointOrPruneCell(cell: MatrixCell) {
    val raw = InMemoryMutationJournalStorage()
    val storage = FailPointJournalStorage(raw)
    val mutations = MatrixMutations(effectful = false)
    val backend = FakeBackend().apply { dedupingPushBehavior = true }
    val state = MatrixState()
    val clock = TestWallClock(50_000L)
    val key = MutationsTestKey(cell.name, MATRIX_NAMESPACE)
    val first = openMatrixEngine(storage, mutations.registry, backend, state, clock)
    val mutationId = first.mutate(key, mutations.set, "checkpoint-value")
    val sequence = raw.sequenceOf(mutationId)
    storage.arm(cell)

    assertJournalDeath(cell, storage) { first.drain(key) }

    val reopened =
        openMatrixEngine(
            FailPointJournalStorage(raw),
            mutations.registry,
            backend,
            state,
            clock,
        )
    reopened.drain(key)

    val persisted = raw.matrixClientState()
    assertEquals(sequence, persisted.client.serverConfirmedRetiredThroughSequence)
    assertTrue(persisted.executionSequences.none { it == sequence })
    assertTrue(persisted.intentSequences.none { it == sequence })
    assertTrue(reopened.pendingWrites().none { it.mutationId == mutationId })
    assertEquals(1, backend.effectivePushApplications.size)
    assertEveryEffectivePushAppliedOnce(backend)
    assertPushHighWater(backend, mutationId, expectedReceipts = 1, expectedDistinctKeys = 1)
    assertEquals(
        if (
            cell.boundary == JournalFailPointBoundary.CHECKPOINT_CONFIRMATION &&
            cell.mode == MatrixKillMode.BEFORE_COMMIT
        ) {
            2
        } else {
            1
        },
        backend.retirementRequests.size,
    )
}

private suspend fun assertIdentityParkCell(cell: MatrixCell) {
    val raw = InMemoryMutationJournalStorage()
    val mutations = MatrixMutations(effectful = false)
    val backend = retainingMatrixBackend()
    val state = MatrixState()
    val clock = TestWallClock(60_000L)
    val key = MutationsTestKey(cell.name, MATRIX_NAMESPACE)
    val setup =
        openMatrixEngine(
            FailPointJournalStorage(raw),
            mutations.registry,
            backend,
            state,
            clock,
        )
    val mutationId = setup.mutate(key, mutations.set, "identity-value")
    val failingResolver =
        MutationKeyResolver<MutationsTestKey> {
            throw IllegalStateException("matrix resolver unavailable")
        }
    val storage = FailPointJournalStorage(raw)
    val first =
        openMatrixEngine(
            storage,
            mutations.registry,
            backend,
            state,
            clock,
            resolver = failingResolver,
        )
    storage.arm(cell)

    assertJournalDeath(cell, storage) { first.drain() }

    val reopened =
        openMatrixEngine(
            FailPointJournalStorage(raw),
            mutations.registry,
            backend,
            state,
            clock,
            resolver = failingResolver,
        )
    reopened.drain()
    assertParked(raw, mutationId, reopened, MutationFailureKind.IDENTITY)
    assertTrue(backend.receivedPushes.isEmpty())
}

private suspend fun assertCodecParkCell(cell: MatrixCell) {
    val raw = InMemoryMutationJournalStorage()
    val mutations = MatrixMutations(effectful = false)
    val missingRegistry: MutatorRegistry<MutationsTestKey, String> = mutatorRegistry { }
    val backend = retainingMatrixBackend()
    val state = MatrixState()
    val clock = TestWallClock(70_000L)
    val key = MutationsTestKey(cell.name, MATRIX_NAMESPACE)
    val setup =
        openMatrixEngine(
            FailPointJournalStorage(raw),
            mutations.registry,
            backend,
            state,
            clock,
        )
    val mutationId = setup.mutate(key, mutations.set, "codec-value")
    val storage = FailPointJournalStorage(raw)
    val first = openMatrixEngine(storage, missingRegistry, backend, state, clock)
    storage.arm(cell)

    assertJournalDeath(cell, storage) { first.drain() }

    val reopened =
        openMatrixEngine(
            FailPointJournalStorage(raw),
            missingRegistry,
            backend,
            state,
            clock,
        )
    reopened.drain()
    assertParked(raw, mutationId, reopened, MutationFailureKind.CODEC)
    assertTrue(backend.receivedPushes.isEmpty())
}

private suspend fun assertSelectorParkCell(cell: MatrixCell) {
    val raw = InMemoryMutationJournalStorage()
    val storage = FailPointJournalStorage(raw)
    val mutations = MatrixMutations(effectful = false)
    val backend = retainingMatrixBackend()
    val state = MatrixState()
    val clock = TestWallClock(80_000L)
    val key = MutationsTestKey(cell.name, MATRIX_NAMESPACE)
    val policy =
        MatrixConflictPolicy(
            precondition = { throw IllegalStateException("matrix selector failed") },
        )
    val first = openMatrixEngine(storage, mutations.registry, backend, state, clock, conflicts = policy)
    val mutationId = first.mutate(key, mutations.set, "selector-value")
    storage.arm(cell)

    assertJournalDeath(cell, storage) { first.drain(key) }

    val reopened =
        openMatrixEngine(
            FailPointJournalStorage(raw),
            mutations.registry,
            backend,
            state,
            clock,
            conflicts = policy,
        )
    reopened.drain(key)
    assertParked(raw, mutationId, reopened, MutationFailureKind.CONFLICT)
    assertTrue(backend.receivedPushes.isEmpty())
}

private suspend fun assertMergeParkCell(cell: MatrixCell) {
    val raw = InMemoryMutationJournalStorage()
    val storage = FailPointJournalStorage(raw)
    val mutations = MatrixMutations(effectful = false)
    val backend = retainingMatrixBackend()
    backend.pushBehavior = { _, _ -> throw matrixConflict(MatrixMeta(3L, "merge")) }
    val state = MatrixState()
    val clock = TestWallClock(90_000L)
    val key = MutationsTestKey(cell.name, MATRIX_NAMESPACE)
    val policy =
        MatrixConflictPolicy(
            merge = { _, _, _ -> throw IllegalStateException("matrix merge failed") },
        )
    val first = openMatrixEngine(storage, mutations.registry, backend, state, clock, conflicts = policy)
    val mutationId = first.mutate(key, mutations.set, "merge-value")
    first.drain(key)
    clock.setEpochMillis(92_000L)
    storage.arm(cell)

    assertJournalDeath(cell, storage) { first.drain(key) }

    val reopened =
        openMatrixEngine(
            FailPointJournalStorage(raw),
            mutations.registry,
            backend,
            state,
            clock,
            conflicts = policy,
        )
    reopened.drain(key)
    assertParked(raw, mutationId, reopened, MutationFailureKind.CONFLICT)
    assertPushHighWater(backend, mutationId, expectedReceipts = 1, expectedDistinctKeys = 1)
    assertTrue(backend.effectivePushApplications.isEmpty())
}

private suspend fun assertUnchangedBoundParkCell(cell: MatrixCell) {
    val raw = InMemoryMutationJournalStorage()
    val storage = FailPointJournalStorage(raw)
    val mutations = MatrixMutations(effectful = false)
    val backend = retainingMatrixBackend()
    backend.pushBehavior = { _, _ -> throw matrixConflict(MatrixMeta(4L, "unchanged")) }
    val state = MatrixState()
    val clock = TestWallClock(100_000L)
    val key = MutationsTestKey(cell.name, MATRIX_NAMESPACE)
    val policy =
        MatrixConflictPolicy(
            merge = { _, mine, _ -> MutationConflictResolution.Retry(mine) },
        )
    val first = openMatrixEngine(storage, mutations.registry, backend, state, clock, conflicts = policy)
    val mutationId = first.mutate(key, mutations.set, "unchanged-value")
    first.drain(key)
    clock.setEpochMillis(102_000L)
    first.drain(key)
    clock.setEpochMillis(104_000L)
    storage.arm(cell)

    assertJournalDeath(cell, storage) { first.drain(key) }

    val reopened =
        openMatrixEngine(
            FailPointJournalStorage(raw),
            mutations.registry,
            backend,
            state,
            clock,
            conflicts = policy,
        )
    reopened.drain(key)
    val parked = assertParked(raw, mutationId, reopened, MutationFailureKind.CONFLICT)
    assertEquals(listOf(1, 2, 3), parked.attempts.map { it.generation })
    assertPushHighWater(
        backend,
        mutationId,
        expectedReceipts = if (cell.mode == MatrixKillMode.BEFORE_COMMIT) 4 else 3,
        expectedDistinctKeys = 3,
    )
    assertTrue(backend.effectivePushApplications.isEmpty())
}

private suspend fun assertProtocolParkCell(cell: MatrixCell) {
    val raw = InMemoryMutationJournalStorage()
    val storage = FailPointJournalStorage(raw)
    val mutations = MatrixMutations(effectful = false)
    val backend = retainingMatrixBackend()
    backend.pushBehavior = { key, value ->
        MutationPresentAck(
            authoritative = value,
            etag = "protocol-$value",
            canonicalKey =
                when {
                    key.canonicalId() == "matrix-temp" -> MutationsTestKey("matrix-real", MATRIX_NAMESPACE)
                    value == "cycle-head" -> MutationsTestKey("matrix-temp", MATRIX_NAMESPACE)
                    else -> null
                },
        )
    }
    val state = MatrixState()
    val clock = TestWallClock(110_000L)
    val source = MutationsTestKey("matrix-temp", MATRIX_NAMESPACE)
    val target = MutationsTestKey("matrix-real", MATRIX_NAMESPACE)
    val first = openMatrixEngine(storage, mutations.registry, backend, state, clock)
    first.mutate(source, mutations.set, "cycle-seed")
    first.drain(source)
    val mutationId = first.mutate(target, mutations.set, "cycle-head")
    storage.arm(cell)

    assertJournalDeath(cell, storage) { first.drain(target) }

    val reopened =
        openMatrixEngine(
            FailPointJournalStorage(raw),
            mutations.registry,
            backend,
            state,
            clock,
        )
    reopened.drain(target)
    assertParked(raw, mutationId, reopened, MutationFailureKind.PROTOCOL)
    assertPushHighWater(
        backend,
        mutationId,
        expectedReceipts = if (cell.mode == MatrixKillMode.BEFORE_COMMIT) 2 else 1,
        expectedDistinctKeys = 1,
        expectedTotalReceipts = if (cell.mode == MatrixKillMode.BEFORE_COMMIT) 3 else 2,
    )
    assertEquals(2, backend.effectivePushApplications.size)
    assertEveryEffectivePushAppliedOnce(backend)
}

private fun FailPointJournalStorage.arm(cell: MatrixCell) {
    check(cell.mode != MatrixKillMode.TRANSPORT)
    val predicate = cell.transition?.let(::matrixTransitionPredicate)
    check((predicate == null) != (cell.boundary == null)) {
        "Every journal cell must select exactly one transition or boundary."
    }
    when (cell.mode) {
        MatrixKillMode.BEFORE_COMMIT ->
            if (predicate != null) {
                armKillBeforeCommit(predicate, cell.occurrence)
            } else {
                armKillBeforeCommit(checkNotNull(cell.boundary), cell.occurrence)
            }
        MatrixKillMode.AFTER_COMMIT ->
            if (predicate != null) {
                armKillAfterCommit(predicate, cell.occurrence)
            } else {
                armKillAfterCommit(checkNotNull(cell.boundary), cell.occurrence)
            }
        MatrixKillMode.TRANSPORT -> error("Transport acknowledgement loss has no journal arm.")
    }
}

private fun matrixTransitionPredicate(
    transition: MatrixTransition,
): (MutationExecutionRecord, MutationExecutionRecord) -> Boolean = { before, after ->
    when (transition) {
        MatrixTransition.PREPARATION ->
            before.phase == StoredPhase.UNPREPARED &&
                after.phase == StoredPhase.READY &&
                after.currentGeneration == 1
        MatrixTransition.INFLIGHT ->
            before.phase == StoredPhase.READY && after.phase == StoredPhase.INFLIGHT
        MatrixTransition.CONFLICT_RECEIPT ->
            before.phase == StoredPhase.INFLIGHT && after.phase == StoredPhase.REFRESH_REQUIRED
        MatrixTransition.RETRY_GENERATION ->
            before.phase == StoredPhase.REFRESH_REQUIRED &&
                after.phase == StoredPhase.READY &&
                after.currentGeneration == before.currentGeneration + 1
        MatrixTransition.SERVER_WINS ->
            before.phase == StoredPhase.REFRESH_REQUIRED && after.phase == StoredPhase.RETIRED
        MatrixTransition.PARK -> after.phase == StoredPhase.PARKED
    }
}

private suspend fun assertJournalDeath(
    cell: MatrixCell,
    storage: FailPointJournalStorage,
    block: suspend () -> Unit,
) {
    val death = assertIs<FailPointProcessDeathException>(captureMatrixFailure(block))
    assertEquals(cell.mode == MatrixKillMode.AFTER_COMMIT, death.committed)
    assertTrue(!storage.hasArmedFailPoint)
}

private suspend fun captureMatrixFailure(block: suspend () -> Unit): Throwable? =
    try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }

private suspend fun assertRetired(
    storage: MutationJournalStorage,
    mutationId: String,
    engine: MutationEngine<MutationsTestKey, String>,
): MatrixDurableState {
    val state = storage.matrixState(mutationId)
    assertEquals(StoredPhase.RETIRED, state.execution.phase)
    assertTrue(engine.pendingWrites().none { it.mutationId == mutationId })
    assertTrue(engine.deadLetters().none { it.mutationId == mutationId })
    return state
}

private suspend fun assertParked(
    storage: MutationJournalStorage,
    mutationId: String,
    engine: MutationEngine<MutationsTestKey, String>,
    failureKind: MutationFailureKind,
): MatrixDurableState {
    val state = storage.matrixState(mutationId)
    assertEquals(StoredPhase.PARKED, state.execution.phase)
    assertEquals(failureKind, assertNotNull(state.activeFailure).kind)
    assertTrue(engine.pendingWrites().none { it.mutationId == mutationId })
    assertEquals(mutationId, engine.deadLetters().single { it.mutationId == mutationId }.mutationId)
    return state
}

private fun assertEveryEffectivePushAppliedOnce(backend: FakeBackend) {
    backend.effectivePushApplications.groupingBy { it }.eachCount().forEach { (_, count) ->
        assertEquals(1, count)
    }
}

private fun assertPushHighWater(
    backend: FakeBackend,
    mutationId: String,
    expectedReceipts: Int,
    expectedDistinctKeys: Int,
    expectedTotalReceipts: Int = expectedReceipts,
) {
    assertEquals(expectedTotalReceipts, backend.receivedPushes.size)
    assertEquals(
        backend.receivedIdempotencyKeys,
        backend.receivedPushes.map { request -> request.idempotencyKey },
    )
    val received = backend.receivedPushes.filter { request -> request.mutationId == mutationId }
    assertEquals(expectedReceipts, received.size)
    received.groupBy { request -> request.generation }.values.forEach { generationReceipts ->
        assertEquals(1, generationReceipts.map { request -> request.idempotencyKey }.distinct().size)
    }
    assertEquals(expectedDistinctKeys, received.map { request -> request.generation }.distinct().size)
    assertEquals(expectedDistinctKeys, received.map { request -> request.idempotencyKey }.distinct().size)
}

private data class MatrixDurableState(
    val execution: MutationExecutionRecord,
    val attempts: List<MutationAttemptRecord>,
    val activeFailure: MutationFailureRecord?,
)

private suspend fun MutationJournalStorage.matrixState(mutationId: String): MatrixDurableState =
    transaction { transaction ->
        val intent =
            transaction.intents(MATRIX_CLIENT_ID).single { intent -> intent.mutationId == mutationId }
        val execution =
            transaction.executions(MATRIX_CLIENT_ID).single { execution ->
                execution.clientSequence == intent.clientSequence
            }
        MatrixDurableState(
            execution = execution,
            attempts =
                transaction.attempts(MATRIX_CLIENT_ID).filter { attempt ->
                    attempt.clientSequence == intent.clientSequence
                },
            activeFailure =
                execution.activeFailureId?.let { activeFailureId ->
                    transaction.failures(MATRIX_CLIENT_ID).single { failure ->
                        failure.failureId == activeFailureId
                    }
                },
        )
    }

private suspend fun MutationJournalStorage.sequenceOf(mutationId: String): Long =
    transaction { transaction ->
        transaction.intents(MATRIX_CLIENT_ID).single { intent ->
            intent.mutationId == mutationId
        }.clientSequence
    }

private data class MatrixClientState(
    val client: MutationClientRecord,
    val intentSequences: List<Long>,
    val executionSequences: List<Long>,
)

private suspend fun MutationJournalStorage.matrixClientState(): MatrixClientState =
    transaction { transaction ->
        MatrixClientState(
            client = checkNotNull(transaction.client(MATRIX_CLIENT_ID)),
            intentSequences = transaction.intents(MATRIX_CLIENT_ID).map { it.clientSequence },
            executionSequences = transaction.executions(MATRIX_CLIENT_ID).map { it.clientSequence },
        )
    }

private class MatrixMutations(effectful: Boolean) {
    lateinit var set: MutatorRef<MutationsTestKey, String, String>

    private val staleTargets =
        if (effectful) {
            typedStales<String>(
                keys = setOf(MATRIX_EFFECT_KEY),
                namespaces = setOf(MATRIX_EFFECT_NAMESPACE),
            )
        } else {
            noStales<MutationsTestKey, String>()
        }

    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            set =
                mutator(
                    id = "matrix-set",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = staleTargets,
                ) { _, value -> MutationPresence.Present(value) }
        }
}

private class MatrixState {
    val values: MutableMap<KeyIdentity, String> = mutableMapOf()
    val staleKeys: MutableList<KeyIdentity> = mutableListOf()
    val staleNamespaces: MutableList<StoreNamespace> = mutableListOf()
}

private class MatrixHandle(
    private val state: MatrixState,
) : StoreWriteHandle<MutationsTestKey, String> {
    override suspend fun apply(
        key: MutationsTestKey,
        value: String,
    ) {
        state.values[key.identity()] = value
    }

    override suspend fun markStale(key: MutationsTestKey) {
        state.staleKeys += key.identity()
    }

    override suspend fun confirmFresh(
        key: MutationsTestKey,
        etag: String?,
    ) = Unit
}

private data class MatrixConflictPolicy(
    val precondition: ((MutationPreconditionCandidate<MutationsTestKey, String>) -> StoreMeta?)? = null,
    val merge:
        ((MutationPresence<String>, MutationPresence<String>, MutationPresence<String>) ->
            MutationConflictResolution<String>)? = null,
)

private fun openMatrixEngine(
    storage: MutationJournalStorage,
    registry: MutatorRegistry<MutationsTestKey, String>,
    server: MutationServer<MutationsTestKey, String>,
    state: MatrixState,
    clock: TestWallClock,
    resolver: MutationKeyResolver<MutationsTestKey> = MATRIX_KEY_RESOLVER,
    conflicts: MatrixConflictPolicy? = null,
): MutationEngine<MutationsTestKey, String> {
    val journal =
        StorageBackedMutationJournal<String>(
            storage = storage,
            registrations = registry.registrations,
            clientId = MATRIX_CLIENT_ID,
            hydrateOnFirstUse = true,
        )
    return MutationEngine(
        registry = registry,
        server = server,
        journal = journal,
        keyResolver = resolver,
        valueCodecVersion = 1,
        valueCodec = FixtureStringArgsCodec,
        conflicts =
            conflicts?.let { policy ->
                MutationConflictRegistration(policy.precondition, policy.merge)
            },
        baseReader = { key -> state.values[key.identity()] ?: "base" },
        wallClock = clock,
        backoffRandom = Random(MATRIX_MASTER_SEED),
        clientId = MATRIX_CLIENT_ID,
        namespaceInvalidation = { namespace -> state.staleNamespaces += namespace },
    ).also { engine -> engine.bind(MatrixHandle(state)) }
}

private fun retainingMatrixBackend(): FakeBackend =
    FakeBackend().apply {
        dedupingPushBehavior = true
        retireBehavior = { MutationRetirementAck(confirmedThroughSequence = 0L) }
    }

private class MatrixAckLossServer(
    private val delegate: FakeBackend,
) : MutationServer<MutationsTestKey, String> {
    private var loseNextAcknowledgement = true

    override suspend fun push(
        request: MutationPush<MutationsTestKey, String>,
    ): MutationAck<MutationsTestKey, String> {
        val acknowledgement = delegate.push(request)
        if (loseNextAcknowledgement) {
            loseNextAcknowledgement = false
            throw MatrixTransportAckLostException()
        }
        return acknowledgement
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        delegate.retire(request)
}

private class MatrixTransportAckLostException :
    CancellationException("Matrix process death after server application and before ack delivery")

private class MatrixMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String?,
) : StoreMeta

private fun matrixConflict(meta: StoreMeta): StoreException =
    StoreResults.exception(
        StoreResults.conflict(meta, "matrix conflict"),
        IllegalStateException("matrix backend conflict"),
    )

private val MATRIX_KEY_RESOLVER =
    MutationKeyResolver<MutationsTestKey> { identity ->
        MutationsTestKey(identity.canonicalId, StoreNamespace(identity.namespace))
    }

private val MATRIX_NAMESPACE = StoreNamespace("matrix")
private val MATRIX_EFFECT_NAMESPACE = StoreNamespace("matrix-effect-namespace")
private val MATRIX_EFFECT_KEY = MutationsTestKey("matrix-effect-key", MATRIX_NAMESPACE)
private const val MATRIX_CLIENT_ID: String = "matrix-client"
private const val MATRIX_MASTER_SEED: Int = 0x0237_0201

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
