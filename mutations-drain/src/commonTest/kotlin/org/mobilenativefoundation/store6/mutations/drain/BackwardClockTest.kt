@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.mobilenativefoundation.store6.mutations.drain

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class BackwardClockTest {
    @Test
    fun backwardClockNoOpsEscalateUntilClockRecovers() = runTest {
        val fixture = DrainFixture()
        fixture.backend.offline = true
        val store = fixture.openStore()
        val scheduler = RecordingDrainScheduler()
        val coordinator = mutationDrainCoordinator(scheduler, fixture.clock)
        val backoff = DrainBackoff()
        coordinator.register(STORE_NAME, store)
        try {
            store.mutate(DrainTestKey("backward-clock"), fixture.appendRef, "+clock")

            val first =
                assertIs<DrainPassOutcome.Remaining>(
                    scheduler.fireActivation(STORE_NAME),
                )
            fixture.nowMillis += assertNotNull(first.scheduledDelay).inWholeMilliseconds
            assertIs<DrainPassOutcome.Remaining>(scheduler.fireActivation(STORE_NAME))
            val attemptBeforeJump = store.pendingWrites().single().attempt
            assertEquals(2, attemptBeforeJump)

            fixture.nowMillis -= backoff.maxDelay.inWholeMilliseconds
            val noProgressDelays = mutableListOf<Duration>()
            repeat(3) {
                val outcome =
                    assertIs<DrainPassOutcome.Remaining>(
                        scheduler.fireActivation(STORE_NAME),
                    )
                noProgressDelays += assertNotNull(outcome.scheduledDelay)
                assertEquals(attemptBeforeJump, store.pendingWrites().single().attempt)
                assertEquals(emptyList(), fixture.backend.receivedPushes)
            }
            assertTrue(noProgressDelays.last() > noProgressDelays.first())

            fixture.nowMillis += (backoff.maxDelay * 2).inWholeMilliseconds
            fixture.backend.offline = false
            assertIs<DrainPassOutcome.Cleared>(scheduler.fireActivation(STORE_NAME))

            assertEquals(listOf("+clock"), fixture.backend.receivedPushes)
            assertEquals(emptyList(), store.pendingWrites())
        } finally {
            coordinator.close()
            store.close()
        }
    }
}

private const val STORE_NAME: String = "backward-clock"

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
