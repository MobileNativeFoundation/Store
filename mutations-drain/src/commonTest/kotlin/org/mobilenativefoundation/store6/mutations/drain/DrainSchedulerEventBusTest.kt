@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations.drain

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class DrainSchedulerEventBusTest {
    @Test
    fun eventBusDropsOldestNonBlocking() = runTest {
        val fixture = DrainFixture()
        val store = fixture.openStore()
        val scheduler = RecordingDrainScheduler()
        val coordinator = mutationDrainCoordinator(scheduler, fixture.clock)
        try {
            repeat(80) { index ->
                coordinator.register("history-$index", store)
                coordinator.unregister("history-$index")
            }

            coordinator.events.test {
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }

            val releaseCollector = CompletableDeferred<Unit>()
            coordinator.events
                .onEach { releaseCollector.await() }
                .test {
                    coordinator.register("blocked", store)
                    coordinator.unregister("blocked")
                    testScheduler.runCurrent()

                    repeat(100) { index ->
                        coordinator.register("burst-$index", store)
                        coordinator.unregister("burst-$index")
                    }
                    assertEquals("burst-99", scheduler.cancelled.last())

                    releaseCollector.complete(Unit)
                    assertEquals("blocked", assertIs<DrainActivationCancelled>(awaitItem()).storeName)
                    val retained =
                        List(64) {
                            assertIs<DrainActivationCancelled>(awaitItem()).storeName
                        }

                    assertEquals(
                        (36 until 100).map { index -> "burst-$index" },
                        retained,
                    )
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
        } finally {
            coordinator.close()
            store.close()
        }
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
