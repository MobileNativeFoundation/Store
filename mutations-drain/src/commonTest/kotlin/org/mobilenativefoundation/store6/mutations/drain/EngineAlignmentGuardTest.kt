@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations.drain

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EngineAlignmentGuardTest {
    @Test
    fun coordinatorDelaysRemainOutsideEngineBackoffWindow() = runTest {
        val fixture = DrainFixture()
        fixture.backend.offline = true
        val store = fixture.openStore()
        val scheduler = InProcessDrainScheduler(backgroundScope)
        val coordinator = mutationDrainCoordinator(scheduler, fixture.clock)
        coordinator.register(STORE_NAME, store)
        val events = mutableListOf<DrainSchedulerEvent>()
        val eventCollection =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.events.collect(events::add)
            }
        val watch =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.watch(STORE_NAME)
            }
        try {
            waitUntilCurrent { events.any { it is DrainPassCompleted } }
            store.mutate(DrainTestKey("alignment"), fixture.appendRef, "+aligned")
            // The engine advances attempt counters on Dispatchers.Default while scheduler
            // events reach the collector only on the test dispatcher, so every wait must
            // pin the event counts its assertions read, not just the attempt counter.
            waitUntilCurrent {
                store.pendingWrites().singleOrNull()?.attempt == 1 &&
                    events.scheduledDelays().size == 1
            }

            var attempt = store.pendingWrites().single().attempt
            assertEquals(1, attempt)
            assertEquals(1, events.scheduledDelays().size)

            repeat(5) {
                val startsBefore = events.activationStarts()
                val schedulesBefore = events.scheduledDelays().size
                val delay = events.scheduledDelays().last()

                fixture.nowMillis += delay.inWholeMilliseconds
                testScheduler.advanceTimeBy(delay)
                waitUntilCurrent {
                    store.pendingWrites().singleOrNull()?.attempt == attempt + 1 &&
                        events.activationStarts() == startsBefore + 1 &&
                        events.scheduledDelays().size == schedulesBefore + 1
                }

                val nextAttempt = store.pendingWrites().single().attempt
                assertEquals(attempt + 1, nextAttempt)
                assertEquals(startsBefore + 1, events.activationStarts())
                assertEquals(schedulesBefore + 1, events.scheduledDelays().size)
                attempt = nextAttempt
            }
            assertEquals(6, attempt)

            val startsBeforeClear = events.activationStarts()
            val cancellationsBeforeClear = events.activationCancellations()
            val finalDelay = events.scheduledDelays().last()
            fixture.backend.offline = false

            fixture.nowMillis += finalDelay.inWholeMilliseconds
            testScheduler.advanceTimeBy(finalDelay)
            waitUntilCurrent {
                store.pendingWrites().isEmpty() &&
                    events.activationStarts() == startsBeforeClear + 1 &&
                    events.activationCancellations() == cancellationsBeforeClear + 1
            }

            assertEquals(startsBeforeClear + 1, events.activationStarts())
            assertEquals(cancellationsBeforeClear + 1, events.activationCancellations())
            assertEquals(emptyList(), store.pendingWrites())
            assertEquals(listOf("+aligned"), fixture.backend.receivedPushes)
        } finally {
            watch.cancelAndJoin()
            eventCollection.cancelAndJoin()
            coordinator.close()
            store.close()
        }
    }
}

private const val STORE_NAME: String = "alignment"

private fun List<DrainSchedulerEvent>.activationStarts(): Int =
    count { event -> event is DrainActivationStarted }

private fun List<DrainSchedulerEvent>.activationCancellations(): Int =
    count { event -> event is DrainActivationCancelled }

private fun List<DrainSchedulerEvent>.scheduledDelays(): List<Duration> =
    mapNotNull { event ->
        (event as? DrainActivationScheduled)?.delayMillis?.milliseconds
    }

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
