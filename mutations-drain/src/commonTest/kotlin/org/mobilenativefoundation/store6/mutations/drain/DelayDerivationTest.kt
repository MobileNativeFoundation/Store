@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations.drain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.mutations.MutationPendingState
import org.mobilenativefoundation.store6.mutations.PendingIntent
import org.mobilenativefoundation.store6.mutations.drain.internal.DerivationResult
import org.mobilenativefoundation.store6.mutations.drain.internal.DerivationState
import org.mobilenativefoundation.store6.mutations.drain.internal.deriveFollowUp
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class DelayDerivationTest {
    @Test
    fun clearedWhenNoRowsAndNoCheckpointFailure() = runTest {
        val fixture = DrainFixture()
        val store = fixture.openStore()
        try {
            val result =
                deriveFollowUp(
                    rows = store.pendingWrites(),
                    checkpointFailed = false,
                    backoff = DrainBackoff(),
                    state = initialState(),
                )

            assertEquals(null, result.delay)
            assertEquals(0, result.pendingIntents)
        } finally {
            store.close()
        }
    }

    @Test
    fun suffixNeverLowersDelay() = runTest {
        val fixture = DrainFixture()
        fixture.backend.offline = true
        val store = fixture.openStore()
        try {
            val key = DrainTestKey("suffix")
            store.mutate(key, fixture.appendRef, "+head")
            store.drain(key)
            store.mutate(key, fixture.appendRef, "+suffix")
            val rows = store.pendingWrites()
            val backoff = DrainBackoff(initialDelay = 7.seconds)

            assertEquals(listOf(1, 0), rows.map { it.attempt })
            val result =
                deriveFollowUp(
                    rows = rows,
                    checkpointFailed = false,
                    backoff = backoff,
                    state = initialState(),
                )

            assertEquals(backoff.delayFor(1), result.delay)
            assertEquals(2, result.pendingIntents)
        } finally {
            store.close()
        }
    }

    @Test
    fun freshHeadDerivesZero() = runTest {
        val fixture = DrainFixture()
        val store = fixture.openStore()
        try {
            store.mutate(DrainTestKey("fresh"), fixture.appendRef, "+fresh")

            val result =
                deriveFollowUp(
                    rows = store.pendingWrites(),
                    checkpointFailed = false,
                    backoff = DrainBackoff(),
                    state = initialState(),
                )

            assertEquals(Duration.ZERO, result.delay)
            assertEquals(1, result.pendingIntents)
        } finally {
            store.close()
        }
    }

    @Test
    fun multiHeadTakesMinimum() = runTest {
        val fixture = DrainFixture()
        fixture.backend.offline = true
        val store = fixture.openStore()
        try {
            val attemptedKey = DrainTestKey("attempted")
            repeat(3) {
                if (it == 0) {
                    store.mutate(attemptedKey, fixture.appendRef, "+attempted")
                }
                store.drain(attemptedKey)
            }
            store.mutate(DrainTestKey("fresh"), fixture.appendRef, "+fresh")
            val rows = store.pendingWrites()

            assertEquals(listOf(3, 0), rows.map { it.attempt })
            val result =
                deriveFollowUp(
                    rows = rows,
                    checkpointFailed = false,
                    backoff = DrainBackoff(),
                    state = initialState(),
                )

            assertEquals(Duration.ZERO, result.delay)
        } finally {
            store.close()
        }
    }

    @Test
    fun inflightHeadDerivesZero() = runTest {
        val fixture = DrainFixture()
        fixture.backend.pushGate = CompletableDeferred()
        val store = fixture.openStore()
        try {
            store.mutate(DrainTestKey("inflight"), fixture.appendRef, "+inflight")
            val pass = async { store.drain() }
            fixture.backend.pushEntered.awaitFromDefaultContext()
            assertEquals(1, fixture.backend.maxConcurrentPushes)
            pass.cancelAndJoin()
            val rows = store.pendingWrites()

            assertEquals(MutationPendingState.INFLIGHT, rows.single().state)
            val result =
                deriveFollowUp(
                    rows = rows,
                    checkpointFailed = false,
                    backoff = DrainBackoff(),
                    state = initialState(),
                )

            assertEquals(Duration.ZERO, result.delay)
        } finally {
            store.close()
        }
    }

    @Test
    fun adoptingHeadDerivesZero() = runTest {
        val fixture = DrainFixture()
        val store = fixture.openStore(throwingOnceSourceOfTruth())
        try {
            store.mutate(DrainTestKey("adopting"), fixture.appendRef, "+adopting")
            store.drain()
            val rows = store.pendingWrites()

            assertEquals(MutationPendingState.ADOPTING, rows.single().state)
            val result =
                deriveFollowUp(
                    rows = rows,
                    checkpointFailed = false,
                    backoff = DrainBackoff(),
                    state = initialState(),
                )

            assertEquals(Duration.ZERO, result.delay)
        } finally {
            store.close()
        }
    }

    @Test
    fun applyingEffectsHeadDerivesZero() = runTest {
        val fixture = DrainFixture()
        val initialStore = fixture.openStore(throwingOnceSourceOfTruth())
        try {
            initialStore.mutate(
                DrainTestKey("applying-effects"),
                fixture.appendRef,
                "+applying-effects",
            )
            initialStore.drain()
        } finally {
            initialStore.close()
        }
        fixture.storage.transaction { transaction ->
            val adopting = transaction.executions("client-0").single()
            assertEquals(MutationExecutionPhase.ACKED, adopting.phase)
            transaction.advanceExecution(
                adopting.withPhase(MutationExecutionPhase.EFFECTS_PENDING),
            )
        }

        val reopened = fixture.openStore()
        try {
            val rows = reopened.pendingWrites()
            assertEquals(MutationPendingState.APPLYING_EFFECTS, rows.single().state)

            val result =
                deriveFollowUp(
                    rows = rows,
                    checkpointFailed = false,
                    backoff = DrainBackoff(),
                    state = initialState(),
                )

            assertEquals(Duration.ZERO, result.delay)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun refreshingHeadUsesAttemptBackoff() = runTest {
        val fixture = DrainFixture()
        fixture.backend.offline = true
        val initialStore = fixture.openStore()
        try {
            val key = DrainTestKey("refreshing")
            initialStore.mutate(key, fixture.appendRef, "+refreshing")
            initialStore.drain(key)
        } finally {
            initialStore.close()
        }
        fixture.storage.transaction { transaction ->
            val ready = transaction.executions("client-0").single()
            assertEquals(MutationExecutionPhase.READY, ready.phase)
            transaction.advanceExecution(ready.withPhase(MutationExecutionPhase.INFLIGHT))
            transaction.advanceExecution(
                MutationExecutionRecord(
                    clientId = ready.clientId,
                    clientSequence = ready.clientSequence,
                    phase = MutationExecutionPhase.REFRESH_REQUIRED,
                    currentGeneration = ready.currentGeneration,
                    attempt = ready.attempt + 1,
                    lastAttemptAt = assertNotNull(ready.lastAttemptAt) + 1L,
                    activeFailureId = ready.activeFailureId,
                    retiredAt = ready.retiredAt,
                ),
            )
        }

        val reopened = fixture.openStore()
        try {
            val rows = reopened.pendingWrites()
            val backoff = DrainBackoff()
            assertEquals(MutationPendingState.REFRESHING, rows.single().state)
            assertEquals(2, rows.single().attempt)

            val result =
                deriveFollowUp(
                    rows = rows,
                    checkpointFailed = false,
                    backoff = backoff,
                    state = initialState(),
                )

            assertEquals(backoff.delayFor(2), result.delay)
        } finally {
            reopened.close()
        }
    }

    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    @Test
    fun refreshingConflictResetHeadDerivesZero() = runTest {
        val fixture = DrainFixture()
        fixture.backend.offline = true
        val initialStore = fixture.openStore()
        try {
            val key = DrainTestKey("refreshing-conflict-reset")
            initialStore.mutate(key, fixture.appendRef, "+refreshing")
            initialStore.drain(key)
        } finally {
            initialStore.close()
        }
        fixture.storage.transaction { transaction ->
            val ready = transaction.executions("client-0").single()
            assertEquals(MutationExecutionPhase.READY, ready.phase)
            transaction.advanceExecution(ready.withPhase(MutationExecutionPhase.INFLIGHT))
            transaction.advanceExecution(
                MutationExecutionRecord(
                    clientId = ready.clientId,
                    clientSequence = ready.clientSequence,
                    phase = MutationExecutionPhase.REFRESH_REQUIRED,
                    currentGeneration = ready.currentGeneration,
                    attempt = ready.attempt + 1,
                    lastAttemptAt = assertNotNull(ready.lastAttemptAt) + 1L,
                    activeFailureId = ready.activeFailureId,
                    retiredAt = ready.retiredAt,
                ),
            )
        }

        val reopened = fixture.openStore()
        try {
            val refreshing = reopened.pendingWrites().single()
            assertEquals(MutationPendingState.REFRESHING, refreshing.state)
            val conflictResetHead =
                PendingIntent(
                    namespace = refreshing.namespace,
                    canonicalId = refreshing.canonicalId,
                    mutationId = refreshing.mutationId,
                    mutatorId = refreshing.mutatorId,
                    state = refreshing.state,
                    attempt = 0,
                    createdAtEpochMillis = refreshing.createdAtEpochMillis,
                )

            val result =
                deriveFollowUp(
                    rows = listOf(conflictResetHead),
                    checkpointFailed = false,
                    backoff = DrainBackoff(),
                    state = initialState(),
                )

            assertEquals(Duration.ZERO, result.delay)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun checkpointOnlyUsesInitialFloorAndEscalates() = runTest {
        val fixture = DrainFixture()
        val store = fixture.openStore()
        try {
            val rows = store.pendingWrites()
            val backoff =
                DrainBackoff(
                    initialDelay = 10.seconds,
                    multiplier = 2.0,
                    maxDelay = 1.hours,
                )

            val first =
                deriveFollowUp(
                    rows = rows,
                    checkpointFailed = true,
                    backoff = backoff,
                    state = initialState(),
                )
            val second =
                deriveFollowUp(
                    rows = rows,
                    checkpointFailed = true,
                    backoff = backoff,
                    state = first.nextState,
                )
            val third =
                deriveFollowUp(
                    rows = rows,
                    checkpointFailed = true,
                    backoff = backoff,
                    state = second.nextState,
                )

            assertEquals(
                listOf(10.seconds, 10.seconds, 20.seconds),
                listOf(first.delay, second.delay, third.delay),
            )
            assertEquals(
                listOf(0, 1, 2),
                listOf(first, second, third).map { it.nextState.noProgressPasses },
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun bottomNeverEqualsEmpty() = runTest {
        val fixture = DrainFixture()
        val store = fixture.openStore()
        try {
            val result =
                deriveFollowUp(
                    rows = store.pendingWrites(),
                    checkpointFailed = false,
                    backoff = DrainBackoff(),
                    state = initialState(),
                )

            assertEquals(0, result.nextState.noProgressPasses)
            assertEquals(emptyMap(), assertNotNull(result.nextState.previousFingerprint))
        } finally {
            store.close()
        }
    }

    @Test
    fun escalationGrowsToMaxAndResetsOnProgress() = runTest {
        val fixture = DrainFixture()
        val store = fixture.openStore()
        try {
            store.mutate(DrainTestKey("stable"), fixture.appendRef, "+stable")
            val rows = store.pendingWrites()
            val backoff =
                DrainBackoff(
                    initialDelay = 1.seconds,
                    multiplier = 2.0,
                    maxDelay = 4.seconds,
                )
            val results = mutableListOf<DerivationResult>()
            var state = initialState()
            repeat(5) {
                val result =
                    deriveFollowUp(
                        rows = rows,
                        checkpointFailed = false,
                        backoff = backoff,
                        state = state,
                    )
                results += result
                state = result.nextState
            }

            assertEquals(
                listOf(Duration.ZERO, 1.seconds, 2.seconds, 4.seconds, 4.seconds),
                results.map { it.delay },
            )
            assertEquals(listOf(0, 1, 2, 3, 4), results.map { it.nextState.noProgressPasses })

            store.mutate(DrainTestKey("progress"), fixture.appendRef, "+progress")
            val reset =
                deriveFollowUp(
                    rows = store.pendingWrites(),
                    checkpointFailed = false,
                    backoff = backoff,
                    state = state,
                )

            assertEquals(Duration.ZERO, reset.delay)
            assertEquals(0, reset.nextState.noProgressPasses)
        } finally {
            store.close()
        }
    }

    @Test
    fun multiplierOneKeepsConstantFloor() = runTest {
        val fixture = DrainFixture()
        val store = fixture.openStore()
        try {
            store.mutate(DrainTestKey("constant"), fixture.appendRef, "+constant")
            val rows = store.pendingWrites()
            val backoff =
                DrainBackoff(
                    initialDelay = 10.seconds,
                    multiplier = 1.0,
                    maxDelay = 1.hours,
                )
            val first =
                deriveFollowUp(
                    rows = rows,
                    checkpointFailed = false,
                    backoff = backoff,
                    state = initialState(),
                )
            val second =
                deriveFollowUp(
                    rows = rows,
                    checkpointFailed = false,
                    backoff = backoff,
                    state = first.nextState,
                )
            val third =
                deriveFollowUp(
                    rows = rows,
                    checkpointFailed = false,
                    backoff = backoff,
                    state = second.nextState,
                )

            assertEquals(
                listOf(Duration.ZERO, 10.seconds, 10.seconds),
                listOf(first.delay, second.delay, third.delay),
            )
        } finally {
            store.close()
        }
    }
}

private fun initialState(): DerivationState =
    DerivationState(previousFingerprint = null, noProgressPasses = 0)

private fun MutationExecutionRecord.withPhase(
    phase: MutationExecutionPhase,
): MutationExecutionRecord =
    MutationExecutionRecord(
        clientId = clientId,
        clientSequence = clientSequence,
        phase = phase,
        currentGeneration = currentGeneration,
        attempt = attempt,
        lastAttemptAt = lastAttemptAt,
        activeFailureId = activeFailureId,
        retiredAt = retiredAt,
    )

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
