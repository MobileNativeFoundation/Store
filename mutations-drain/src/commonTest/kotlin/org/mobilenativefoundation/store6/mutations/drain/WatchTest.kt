@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations.drain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.mutations.MutationPendingState
import org.mobilenativefoundation.store6.mutations.MutationRetirementAck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class WatchTest {
    @Test
    fun watchRunsUnconditionalLaunchPass() = runTest {
        val harness = WatchHarness()
        harness.fixture.backend.offline = true
        try {
            harness.store.mutate(DrainTestKey("launch"), harness.fixture.appendRef, "+pending")
            harness.store.drain()
            harness.fixture.backend.offline = false
            harness.fixture.nowMillis = 1.hours.inWholeMilliseconds

            val launchPassCompleted = nextPassCompletion(harness.coordinator)
            val watch = launch { harness.coordinator.watch(STORE_NAME) }
            launchPassCompleted.await()

            assertEquals(emptyList(), harness.store.pendingWrites())
            assertEquals(listOf("+pending"), harness.fixture.backend.receivedPushes)
            assertEquals(
                "schedule(users, 1h)",
                harness.scheduler.log.first { entry -> entry.startsWith("schedule(") },
            )
            watch.cancelAndJoin()
        } finally {
            harness.close()
        }
    }

    @Test
    fun watchLaunchPassRetriesCheckpointOnlyWork() = runTest {
        val harness = WatchHarness()
        var retireCalls = 0
        harness.fixture.backend.retireBehavior = {
            retireCalls += 1
            if (retireCalls == 1) {
                error("checkpoint unavailable")
            }
            MutationRetirementAck(confirmedThroughSequence = 1L)
        }
        try {
            harness.store.mutate(DrainTestKey("checkpoint"), harness.fixture.appendRef, "+done")
            harness.store.drain()
            assertEquals(emptyList(), harness.store.pendingWrites())
            assertEquals(1, retireCalls)

            val launchPassCompleted = nextPassCompletion(harness.coordinator)
            val watch = launch { harness.coordinator.watch(STORE_NAME) }
            launchPassCompleted.await()

            assertEquals(2, retireCalls)
            watch.cancelAndJoin()
        } finally {
            harness.close()
        }
    }

    @Test
    fun fastPathAddsNoSchedulerChurn() = runTest {
        val harness = WatchHarness()
        try {
            val launchPassCompleted = nextPassCompletion(harness.coordinator)
            val watch = launch { harness.coordinator.watch(STORE_NAME) }
            launchPassCompleted.await()
            val settledSchedulerLog = harness.scheduler.log.toList()

            val fastPathCompleted = nextPassCompletion(harness.coordinator)
            harness.store.mutate(DrainTestKey("fast"), harness.fixture.appendRef, "+done")
            fastPathCompleted.await()

            assertEquals(emptyList(), harness.store.pendingWrites())
            assertEquals(listOf("+done"), harness.fixture.backend.receivedPushes)
            assertEquals(settledSchedulerLog, harness.scheduler.log)
            watch.cancelAndJoin()
        } finally {
            harness.close()
        }
    }

    @Test
    fun externallyCancelledWatchMidPassIsRecoveredByLaunchPass() = runTest {
        val harness = WatchHarness()
        try {
            val launchPassCompleted = nextPassCompletion(harness.coordinator)
            val watch = launch { harness.coordinator.watch(STORE_NAME) }
            launchPassCompleted.await()
            val gate = CompletableDeferred<Unit>()
            harness.fixture.backend.pushGate = gate

            harness.store.mutate(
                DrainTestKey("externally-cancelled"),
                harness.fixture.appendRef,
                "+recovered",
            )
            harness.fixture.backend.pushEntered.awaitFromDefaultContext()
            assertEquals(1, harness.fixture.backend.maxConcurrentPushes)

            watch.cancelAndJoin()
            assertTrue(watch.isCancelled)
            gate.complete(Unit)
            assertEquals(
                MutationPendingState.INFLIGHT,
                harness.store.pendingWrites().single().state,
            )

            val recoveryPassCompleted = nextPassCompletion(harness.coordinator)
            val recoveryWatch = launch { harness.coordinator.watch(STORE_NAME) }
            recoveryPassCompleted.await()

            assertEquals(emptyList(), harness.store.pendingWrites())
            assertEquals(listOf("+recovered"), harness.fixture.backend.receivedPushes)
            recoveryWatch.cancelAndJoin()
        } finally {
            harness.close()
        }
    }

    @Test
    fun enqueueBurstCoalescesToAtMostTwoPasses() = runTest {
        val harness = WatchHarness()
        val started = mutableListOf<DrainActivationStarted>()
        try {
            val launchPassCompleted = nextPassCompletion(harness.coordinator)
            val watch = launch { harness.coordinator.watch(STORE_NAME) }
            launchPassCompleted.await()
            harness.coordinator.events
                .filterIsInstance<DrainActivationStarted>()
                .onEach(started::add)
                .launchIn(backgroundScope)

            val gate = CompletableDeferred<Unit>()
            harness.fixture.backend.pushGate = gate
            harness.store.mutate(DrainTestKey("burst"), harness.fixture.appendRef, "+0")
            harness.fixture.backend.pushEntered.awaitFromDefaultContext()
            repeat(4) { index ->
                harness.store.mutate(
                    DrainTestKey("burst"),
                    harness.fixture.appendRef,
                    "+${index + 1}",
                )
            }
            gate.complete(Unit)
            awaitUntil { harness.store.pendingWrites().isEmpty() }

            assertTrue(started.isNotEmpty())
            assertTrue(started.size <= 2, "Expected at most two passes, but saw ${started.size}.")
            watch.cancelAndJoin()
        } finally {
            harness.close()
        }
    }

    @Test
    fun drainOnEnqueueFalseSchedulesZero() = runTest {
        val harness = WatchHarness(policy = DrainPolicy(drainOnEnqueue = false))
        val started = mutableListOf<DrainActivationStarted>()
        try {
            harness.coordinator.events
                .filterIsInstance<DrainActivationStarted>()
                .onEach(started::add)
                .launchIn(backgroundScope)
            val launchPassCompleted = nextPassCompletion(harness.coordinator)
            val watch = launch { harness.coordinator.watch(STORE_NAME) }
            launchPassCompleted.await()
            val launchPasses = started.size
            val launchScheduleCount = harness.scheduler.scheduled.size

            harness.store.mutate(DrainTestKey("scheduled"), harness.fixture.appendRef, "+pending")
            awaitUntil { harness.scheduler.scheduled.size == launchScheduleCount + 1 }

            assertEquals(launchPasses, started.size)
            assertEquals(launchScheduleCount + 1, harness.scheduler.scheduled.size)
            assertEquals(Duration.ZERO, harness.scheduler.scheduled.last().earliestDelay)
            assertEquals(emptyList(), harness.fixture.backend.receivedPushes)
            watch.cancelAndJoin()
        } finally {
            harness.close()
        }
    }

    @Test
    fun enqueueRacingWatchStartupIsCovered() = runTest {
        val harness = WatchHarness()
        try {
            harness.store.mutate(DrainTestKey("startup"), harness.fixture.appendRef, "+pending")

            val launchPassCompleted = nextPassCompletion(harness.coordinator)
            val watch = launch { harness.coordinator.watch(STORE_NAME) }
            launchPassCompleted.await()

            assertEquals(emptyList(), harness.store.pendingWrites())
            assertEquals(listOf("+pending"), harness.fixture.backend.receivedPushes)
            watch.cancelAndJoin()
        } finally {
            harness.close()
        }
    }

    @Test
    fun nonEnqueueEventsIgnored() = runTest {
        val harness = WatchHarness()
        val started = mutableListOf<DrainActivationStarted>()
        try {
            harness.coordinator.events
                .filterIsInstance<DrainActivationStarted>()
                .onEach(started::add)
                .launchIn(backgroundScope)
            val launchPassCompleted = nextPassCompletion(harness.coordinator)
            val watch = launch { harness.coordinator.watch(STORE_NAME) }
            launchPassCompleted.await()
            val launchPasses = started.size

            val fastPathCompleted = nextPassCompletion(harness.coordinator)
            harness.store.mutate(DrainTestKey("events"), harness.fixture.appendRef, "+done")
            fastPathCompleted.await()

            assertEquals(launchPasses + 1, started.size)
            assertEquals(emptyList(), harness.store.pendingWrites())
            watch.cancelAndJoin()
        } finally {
            harness.close()
        }
    }

    @Test
    fun unregisterCancelsWatch() = runTest {
        val harness = WatchHarness()
        try {
            val launchPassCompleted = nextPassCompletion(harness.coordinator)
            val watch = launch(start = CoroutineStart.UNDISPATCHED) {
                harness.coordinator.watch(STORE_NAME)
            }
            launchPassCompleted.await()

            harness.coordinator.unregister(STORE_NAME)
            watch.join()

            assertTrue(watch.isCancelled)
        } finally {
            harness.close()
        }
    }

    @Test
    fun closeCancelsWatch() = runTest {
        val harness = WatchHarness()
        try {
            val launchPassCompleted = nextPassCompletion(harness.coordinator)
            val watch = launch(start = CoroutineStart.UNDISPATCHED) {
                harness.coordinator.watch(STORE_NAME)
            }
            launchPassCompleted.await()

            harness.coordinator.close()
            watch.join()

            assertTrue(watch.isCancelled)
        } finally {
            harness.close()
        }
    }

    @Test
    fun watchUnknownNameThrows() = runTest {
        val fixture = DrainFixture()
        val store = fixture.openStore()
        val coordinator = mutationDrainCoordinator(RecordingDrainScheduler(), fixture.clock)
        try {
            assertFailsWith<IllegalArgumentException> {
                coordinator.watch("unknown")
            }
        } finally {
            coordinator.close()
            store.close()
        }
    }

    @Test
    fun watchAfterCloseThrows() = runTest {
        val coordinator = mutationDrainCoordinator(RecordingDrainScheduler())
        coordinator.close()

        assertFailsWith<IllegalStateException> {
            coordinator.watch(STORE_NAME)
        }
    }
}

private const val STORE_NAME: String = "users"

private class WatchHarness(
    policy: DrainPolicy = DrainPolicy(),
) {
    val fixture = DrainFixture()
    val store = fixture.openStore()
    val scheduler = RecordingDrainScheduler()
    val coordinator = mutationDrainCoordinator(scheduler, fixture.clock)

    init {
        coordinator.register(STORE_NAME, store, policy)
    }

    fun close() {
        coordinator.close()
        store.close()
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)

private fun TestScope.nextPassCompletion(
    coordinator: MutationDrainCoordinator,
) = async(start = CoroutineStart.UNDISPATCHED) {
    coordinator.events.filterIsInstance<DrainPassCompleted>().first()
}
