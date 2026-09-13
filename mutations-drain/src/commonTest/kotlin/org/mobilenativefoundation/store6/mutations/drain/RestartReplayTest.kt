@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations.drain

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class RestartReplayTest {
    @Test
    fun watchLaunchPassReplaysJournalAfterStoreRestart() = runTest {
        val fixture = DrainFixture()
        fixture.backend.offline = true
        val firstSession = fixture.openStore()
        try {
            firstSession.mutate(
                DrainTestKey("restart"),
                fixture.appendRef,
                "+replayed",
            )
        } finally {
            firstSession.close()
        }

        fixture.backend.offline = false
        val secondSession = fixture.openStore()
        val scheduler = InProcessDrainScheduler(backgroundScope)
        val coordinator = mutationDrainCoordinator(scheduler, fixture.clock)
        coordinator.register(STORE_NAME, secondSession)
        val watch =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.watch(STORE_NAME)
            }
        try {
            waitUntilCurrent {
                fixture.backend.receivedPushes == listOf("+replayed") &&
                    secondSession.pendingWrites().isEmpty()
            }

            assertEquals(listOf("+replayed"), fixture.backend.receivedPushes)
            assertEquals(emptyList(), secondSession.pendingWrites())
        } finally {
            watch.cancelAndJoin()
            coordinator.close()
            secondSession.close()
        }
    }
}

private const val STORE_NAME: String = "restart"

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
