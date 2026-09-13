package org.mobilenativefoundation.store6.mutations.drain.meeseeks

import dev.mattramotar.meeseeks.runtime.AppContext
import dev.mattramotar.meeseeks.runtime.RuntimeContext
import dev.mattramotar.meeseeks.runtime.TaskResult
import dev.mattramotar.meeseeks.runtime.Worker
import kotlinx.coroutines.CancellationException
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.mutations.drain.meeseeks.internal.toTaskResult

/**
 * The Meeseeks worker for drain activations. Register it in the host's `Meeseeks.initialize`
 * block. Each run delegates the payload's registration name to the scheduler's attached
 * coordinator.
 */
@ExperimentalStoreApi
public class StoreDrainWorker(
    appContext: AppContext,
    private val scheduler: MeeseeksDrainScheduler,
) : Worker<StoreDrainPayload>(appContext) {
    @ExperimentalStoreApi
    override suspend fun run(
        payload: StoreDrainPayload,
        context: RuntimeContext,
    ): TaskResult =
        try {
            scheduler.runActivation(payload.storeName).toTaskResult()
        } catch (failure: CancellationException) {
            throw failure
        }
}
