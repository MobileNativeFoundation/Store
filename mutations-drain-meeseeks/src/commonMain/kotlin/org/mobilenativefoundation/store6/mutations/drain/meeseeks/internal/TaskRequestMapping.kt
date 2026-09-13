@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain.meeseeks.internal

import dev.mattramotar.meeseeks.runtime.TaskPreconditions
import dev.mattramotar.meeseeks.runtime.TaskRequest
import dev.mattramotar.meeseeks.runtime.TaskResult
import dev.mattramotar.meeseeks.runtime.TaskRetryPolicy
import dev.mattramotar.meeseeks.runtime.TaskSchedule
import kotlin.time.Duration.Companion.seconds
import org.mobilenativefoundation.store6.mutations.drain.DrainPassOutcome
import org.mobilenativefoundation.store6.mutations.drain.DrainRequest
import org.mobilenativefoundation.store6.mutations.drain.meeseeks.StoreDrainPayload

internal fun DrainRequest.toTaskRequest(): TaskRequest = TaskRequest(
    payload = StoreDrainPayload(storeName),
    preconditions = TaskPreconditions(
        requiresNetwork = constraints.requiresNetwork,
        requiresCharging = constraints.requiresCharging,
        requiresBatteryNotLow = false,
    ),
    schedule = TaskSchedule.OneTime(initialDelay = earliestDelay),
    retryPolicy = TaskRetryPolicy.FixedInterval(
        retryInterval = 30.seconds,
        maxRetries = null,
    ),
)

internal fun DrainPassOutcome.toTaskResult(): TaskResult = when (this) {
    is DrainPassOutcome.Cleared -> TaskResult.Success
    is DrainPassOutcome.Remaining -> {
        if (scheduledDelay != null) TaskResult.Success else TaskResult.Retry
    }
    is DrainPassOutcome.Unavailable -> {
        TaskResult.Failure.Transient(error = IllegalStateException(reason))
    }
}
