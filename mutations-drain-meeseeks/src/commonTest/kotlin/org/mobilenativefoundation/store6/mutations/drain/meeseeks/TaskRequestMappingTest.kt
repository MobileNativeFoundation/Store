@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain.meeseeks

import dev.mattramotar.meeseeks.runtime.TaskPriority
import dev.mattramotar.meeseeks.runtime.TaskResult
import dev.mattramotar.meeseeks.runtime.TaskRetryPolicy
import dev.mattramotar.meeseeks.runtime.TaskSchedule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mobilenativefoundation.store6.mutations.drain.DrainConstraints
import org.mobilenativefoundation.store6.mutations.drain.DrainPassOutcome
import org.mobilenativefoundation.store6.mutations.drain.DrainRequest
import org.mobilenativefoundation.store6.mutations.drain.meeseeks.internal.toTaskRequest
import org.mobilenativefoundation.store6.mutations.drain.meeseeks.internal.toTaskResult

class TaskRequestMappingTest {
    @Test
    fun drainRequestMapsEveryTaskRequestField() {
        val request = DrainRequest(
            storeName = "users",
            constraints = DrainConstraints(
                requiresNetwork = false,
                requiresCharging = true,
            ),
            earliestDelay = 7.minutes,
        )

        val task = request.toTaskRequest()

        val payload = assertIs<StoreDrainPayload>(task.payload)
        assertEquals("users", payload.storeName)
        assertEquals(false, task.preconditions.requiresNetwork)
        assertEquals(true, task.preconditions.requiresCharging)
        assertEquals(false, task.preconditions.requiresBatteryNotLow)
        assertEquals(TaskPriority.MEDIUM, task.priority)
        assertEquals(7.minutes, assertIs<TaskSchedule.OneTime>(task.schedule).initialDelay)
        val retryPolicy = assertIs<TaskRetryPolicy.FixedInterval>(task.retryPolicy)
        assertEquals(30.seconds, retryPolicy.retryInterval)
        assertNull(retryPolicy.maxRetries)
    }

    @Test
    fun payloadRoundTripsThroughJson() {
        val encoded = Json.encodeToString(StoreDrainPayload(storeName = "users"))

        val decoded = Json.decodeFromString<StoreDrainPayload>(encoded)

        assertEquals("users", decoded.storeName)
    }

    @Test
    fun clearedMapsToSuccess() {
        assertEquals(TaskResult.Success, DrainPassOutcome.Cleared().toTaskResult())
    }

    @Test
    fun remainingWithScheduledDelayMapsToSuccess() {
        val outcome = DrainPassOutcome.Remaining(
            pendingIntents = 1,
            scheduledDelay = 30.seconds,
        )

        assertEquals(TaskResult.Success, outcome.toTaskResult())
    }

    @Test
    fun remainingWithoutScheduledDelayMapsToRetry() {
        val outcome = DrainPassOutcome.Remaining(
            pendingIntents = 1,
            scheduledDelay = null,
        )

        assertEquals(TaskResult.Retry, outcome.toTaskResult())
    }

    @Test
    fun unavailableMapsToTransientFailure() {
        val outcome = DrainPassOutcome.Unavailable(
            storeName = "users",
            reason = "Registration is not available.",
        )

        val result = assertIs<TaskResult.Failure.Transient>(outcome.toTaskResult())

        val error = assertIs<IllegalStateException>(result.error)
        assertEquals("Registration is not available.", error.message)
    }
}
