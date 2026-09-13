@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations.drain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class InProcessDrainSchedulerTest {
    @Test
    fun firesAfterDelayThroughCoordinator() = runTest {
        val harness = InProcessHarness(backgroundScope)
        try {
            harness.store.mutate(DrainTestKey("delayed"), harness.fixture.appendRef, "+done")

            harness.scheduler.schedule(request(delay = 10.seconds))
            testScheduler.advanceTimeBy(9_999L)
            testScheduler.runCurrent()
            assertEquals(emptyList(), harness.fixture.backend.receivedPushes)

            testScheduler.advanceTimeBy(1L)
            waitUntilCurrent {
                harness.fixture.backend.receivedPushes == listOf("+done") &&
                    harness.store.pendingWrites().isEmpty()
            }
            assertEquals(listOf("+done"), harness.fixture.backend.receivedPushes)
        } finally {
            harness.close()
        }
    }

    @Test
    fun replaceSupersedesPendingTimer() = runTest {
        val harness = InProcessHarness(backgroundScope)
        try {
            harness.store.mutate(DrainTestKey("replace"), harness.fixture.appendRef, "+done")

            harness.scheduler.schedule(request(delay = 10.seconds))
            testScheduler.advanceTimeBy(3_000L)
            harness.scheduler.schedule(request(delay = 20.seconds))

            testScheduler.advanceTimeBy(19_999L)
            testScheduler.runCurrent()
            assertEquals(emptyList(), harness.fixture.backend.receivedPushes)

            testScheduler.advanceTimeBy(1L)
            waitUntilCurrent {
                harness.fixture.backend.receivedPushes == listOf("+done") &&
                    harness.store.pendingWrites().isEmpty()
            }
            assertEquals(listOf("+done"), harness.fixture.backend.receivedPushes)
        } finally {
            harness.close()
        }
    }

    @Test
    fun cancelPreventsFiring() = runTest {
        val harness = InProcessHarness(backgroundScope)
        try {
            harness.store.mutate(DrainTestKey("cancel"), harness.fixture.appendRef, "+pending")

            harness.scheduler.schedule(request(delay = 10.seconds))
            harness.scheduler.cancel(STORE_NAME)
            testScheduler.advanceTimeBy(10_000L)
            testScheduler.runCurrent()

            assertEquals(emptyList(), harness.fixture.backend.receivedPushes)
            assertEquals(1, harness.store.pendingWrites().size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun deadScopeSchedulesThrow() = runTest {
        val deadScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val scheduler = InProcessDrainScheduler(deadScope)
        val coordinator = mutationDrainCoordinator(scheduler)
        deadScope.cancel()
        try {
            assertFailsWith<IllegalStateException> {
                scheduler.schedule(request(delay = Duration.ZERO))
            }
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun concurrentScheduleKeepsOneTimer() = runTest {
        val harness = InProcessHarness(backgroundScope)
        try {
            harness.store.mutate(DrainTestKey("concurrent"), harness.fixture.appendRef, "+done")

            coroutineScope {
                repeat(32) {
                    launch {
                        harness.scheduler.schedule(request(delay = 10.seconds))
                    }
                }
            }
            testScheduler.advanceTimeBy(10_000L)
            waitUntilCurrent {
                harness.fixture.backend.receivedPushes == listOf("+done") &&
                    harness.store.pendingWrites().isEmpty()
            }

            assertEquals(listOf("+done"), harness.fixture.backend.receivedPushes)
        } finally {
            harness.close()
        }
    }

    @Test
    fun runningActivationNotCancelledByReplace() = runTest {
        val harness = InProcessHarness(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<DrainSchedulerEvent>()
        harness.fixture.backend.offline = true
        harness.fixture.backend.pushGate = gate
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            harness.coordinator.events.collect(events::add)
        }
        try {
            harness.store.mutate(DrainTestKey("running"), harness.fixture.appendRef, "+pending")
            harness.scheduler.schedule(request(delay = Duration.ZERO))
            waitUntilCurrent { events.count { it is DrainActivationStarted } == 1 }
            assertEquals(1, events.count { it is DrainActivationStarted })

            harness.scheduler.schedule(request(delay = 10.seconds))
            harness.coordinator.close()
            gate.complete(Unit)
            waitUntilCurrent { events.count { it is DrainPassCompleted } == 1 }
            assertEquals(1, events.count { it is DrainPassCompleted })

            testScheduler.advanceTimeBy(9_999L)
            testScheduler.runCurrent()
            assertEquals(0, events.count { it is DrainPassFailed })

            testScheduler.advanceTimeBy(1L)
            waitUntilCurrent { events.count { it is DrainPassFailed } == 1 }
            assertEquals(1, events.count { it is DrainPassFailed })
        } finally {
            harness.close()
        }
    }

    @Test
    fun preAttachScheduleThrows() = runTest {
        val scheduler = InProcessDrainScheduler(backgroundScope)

        assertFailsWith<IllegalStateException> {
            scheduler.schedule(request(delay = Duration.ZERO))
        }
    }
}

private const val STORE_NAME: String = "users"

private class InProcessHarness(
    scope: CoroutineScope,
) {
    val fixture = DrainFixture()
    val store = fixture.openStore()
    val scheduler = InProcessDrainScheduler(scope)
    val coordinator = mutationDrainCoordinator(scheduler, fixture.clock)

    init {
        coordinator.register(STORE_NAME, store)
    }

    fun close() {
        coordinator.close()
        store.close()
    }
}

private fun request(delay: Duration): DrainRequest =
    DrainRequest(
        storeName = STORE_NAME,
        constraints = DrainConstraints(),
        earliestDelay = delay,
    )

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
