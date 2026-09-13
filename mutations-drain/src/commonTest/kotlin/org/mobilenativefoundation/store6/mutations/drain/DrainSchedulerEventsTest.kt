@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain

import kotlin.test.Test
import kotlin.test.assertEquals

class DrainSchedulerEventsTest {
    @Test
    fun everyEventPropertyRoundTrips() {
        val scheduled =
            DrainSchedulerEventTestFactory.activationScheduled(
                storeName = "scheduled",
                occurredAtEpochMillis = 1L,
                delayMillis = 2L,
            )
        assertEquals("scheduled", scheduled.storeName)
        assertEquals(1L, scheduled.occurredAtEpochMillis)
        assertEquals(2L, scheduled.delayMillis)

        val started =
            DrainSchedulerEventTestFactory.activationStarted(
                storeName = "started",
                occurredAtEpochMillis = 3L,
            )
        assertEquals("started", started.storeName)
        assertEquals(3L, started.occurredAtEpochMillis)

        val completed =
            DrainSchedulerEventTestFactory.passCompleted(
                storeName = "completed",
                occurredAtEpochMillis = 4L,
                pendingIntents = 5,
                deadLetters = 6,
            )
        assertEquals("completed", completed.storeName)
        assertEquals(4L, completed.occurredAtEpochMillis)
        assertEquals(5, completed.pendingIntents)
        assertEquals(6, completed.deadLetters)

        val passFailed =
            DrainSchedulerEventTestFactory.passFailed(
                storeName = "pass-failed",
                occurredAtEpochMillis = 7L,
                message = "pass failure",
            )
        assertEquals("pass-failed", passFailed.storeName)
        assertEquals(7L, passFailed.occurredAtEpochMillis)
        assertEquals("pass failure", passFailed.message)

        val scheduleFailed =
            DrainSchedulerEventTestFactory.scheduleFailed(
                storeName = "schedule-failed",
                occurredAtEpochMillis = 8L,
                message = "schedule failure",
            )
        assertEquals("schedule-failed", scheduleFailed.storeName)
        assertEquals(8L, scheduleFailed.occurredAtEpochMillis)
        assertEquals("schedule failure", scheduleFailed.message)

        val cancelled =
            DrainSchedulerEventTestFactory.activationCancelled(
                storeName = "cancelled",
                occurredAtEpochMillis = 9L,
            )
        assertEquals("cancelled", cancelled.storeName)
        assertEquals(9L, cancelled.occurredAtEpochMillis)
    }
}

internal object DrainSchedulerEventTestFactory {
    internal fun activationScheduled(
        storeName: String,
        occurredAtEpochMillis: Long,
        delayMillis: Long,
    ): DrainActivationScheduled =
        DrainActivationScheduled(
            storeName = storeName,
            occurredAtEpochMillis = occurredAtEpochMillis,
            delayMillis = delayMillis,
        )

    internal fun activationStarted(
        storeName: String,
        occurredAtEpochMillis: Long,
    ): DrainActivationStarted =
        DrainActivationStarted(
            storeName = storeName,
            occurredAtEpochMillis = occurredAtEpochMillis,
        )

    internal fun passCompleted(
        storeName: String,
        occurredAtEpochMillis: Long,
        pendingIntents: Int,
        deadLetters: Int,
    ): DrainPassCompleted =
        DrainPassCompleted(
            storeName = storeName,
            occurredAtEpochMillis = occurredAtEpochMillis,
            pendingIntents = pendingIntents,
            deadLetters = deadLetters,
        )

    internal fun passFailed(
        storeName: String,
        occurredAtEpochMillis: Long,
        message: String,
    ): DrainPassFailed =
        DrainPassFailed(
            storeName = storeName,
            occurredAtEpochMillis = occurredAtEpochMillis,
            message = message,
        )

    internal fun scheduleFailed(
        storeName: String,
        occurredAtEpochMillis: Long,
        message: String,
    ): DrainScheduleFailed =
        DrainScheduleFailed(
            storeName = storeName,
            occurredAtEpochMillis = occurredAtEpochMillis,
            message = message,
        )

    internal fun activationCancelled(
        storeName: String,
        occurredAtEpochMillis: Long,
    ): DrainActivationCancelled =
        DrainActivationCancelled(
            storeName = storeName,
            occurredAtEpochMillis = occurredAtEpochMillis,
        )
}
