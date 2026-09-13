package org.mobilenativefoundation.store6.mutations.drain.meeseeks

import dev.mattramotar.meeseeks.runtime.BGTaskManager
import dev.mattramotar.meeseeks.runtime.ScheduledTask
import dev.mattramotar.meeseeks.runtime.TaskId
import dev.mattramotar.meeseeks.runtime.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.mutations.drain.DrainConstraints
import org.mobilenativefoundation.store6.mutations.drain.DrainPassOutcome
import org.mobilenativefoundation.store6.mutations.drain.DrainRequest
import org.mobilenativefoundation.store6.mutations.drain.DrainScheduler
import org.mobilenativefoundation.store6.mutations.drain.MutationDrainCoordinator
import org.mobilenativefoundation.store6.mutations.drain.meeseeks.internal.drainPlatformName
import org.mobilenativefoundation.store6.mutations.drain.meeseeks.internal.toTaskRequest
import org.mobilenativefoundation.store6.mutations.drain.meeseeks.internal.unsupportedConstraintKeys

/**
 * A [DrainScheduler] backed by a host-initialized Meeseeks [BGTaskManager]. The host owns
 * `Meeseeks.initialize`, worker registrations, and platform setup. [manager] must return the same
 * manager instance for this scheduler's lifetime.
 */
@ExperimentalStoreApi
public class MeeseeksDrainScheduler(
    private val manager: () -> BGTaskManager,
) : DrainScheduler {
    private val attached = MutableStateFlow<MutationDrainCoordinator?>(null)
    private val tracked = MutableStateFlow<Map<String, TaskId>>(emptyMap())

    internal val trackedTaskIds: Map<String, TaskId>
        get() = tracked.value

    @ExperimentalStoreApi
    override fun attach(coordinator: MutationDrainCoordinator) {
        check(attached.compareAndSet(expect = null, update = coordinator)) {
            "MeeseeksDrainScheduler is already attached."
        }
    }

    @ExperimentalStoreApi
    override fun validate(constraints: DrainConstraints) {
        val unsupported = unsupportedConstraintKeys(constraints)
        require(unsupported.isEmpty()) {
            "MeeseeksDrainScheduler does not support ${unsupported.joinToString()} on " +
                "$drainPlatformName. Use " +
                "DrainConstraints(requiresNetwork = false, requiresCharging = false) or " +
                "InProcessDrainScheduler."
        }
    }

    @ExperimentalStoreApi
    override fun schedule(request: DrainRequest) {
        requireAttached()
        val taskManager = manager()
        val taskRequest = request.toTaskRequest()
        val trackedId = tracked.value[request.storeName]
        if (trackedId != null) {
            when (taskManager.getTaskStatus(trackedId)) {
                TaskStatus.Pending -> {
                    putTracked(
                        request.storeName,
                        taskManager.reschedule(trackedId, taskRequest),
                    )
                    return
                }

                TaskStatus.Running -> {
                    putTracked(request.storeName, taskManager.schedule(taskRequest))
                    return
                }

                null,
                is TaskStatus.Finished,
                -> removeIfCurrent(request.storeName, trackedId)
            }
        }

        recoverOrSchedule(
            taskManager = taskManager,
            request = request,
        )
    }

    @ExperimentalStoreApi
    override fun cancel(storeName: String) {
        requireAttached()
        val trackedId = tracked.value[storeName] ?: return
        val taskManager = manager()
        if (taskManager.getTaskStatus(trackedId) == TaskStatus.Pending) {
            taskManager.cancel(trackedId)
        }
        removeIfCurrent(storeName, trackedId)
    }

    internal suspend fun runActivation(storeName: String): DrainPassOutcome =
        requireAttached().runActivation(storeName)

    private fun recoverOrSchedule(
        taskManager: BGTaskManager,
        request: DrainRequest,
    ) {
        val matching = taskManager.listTasks().filter { it.matches(request.storeName) }
        val pending = matching.firstOrNull { it.status == TaskStatus.Pending }
        if (pending != null) {
            putTracked(
                request.storeName,
                taskManager.reschedule(pending.id, request.toTaskRequest()),
            )
            return
        }

        val running = matching.firstOrNull { it.status == TaskStatus.Running }
        if (running != null) {
            putTracked(request.storeName, running.id)
            return
        }

        putTracked(request.storeName, taskManager.schedule(request.toTaskRequest()))
    }

    private fun ScheduledTask.matches(storeName: String): Boolean =
        runCatching {
            (task.payload as? StoreDrainPayload)?.storeName
        }.getOrNull() == storeName

    private fun requireAttached(): MutationDrainCoordinator =
        checkNotNull(attached.value) { "MeeseeksDrainScheduler is not attached." }

    private fun putTracked(
        storeName: String,
        taskId: TaskId,
    ) {
        while (true) {
            val current = tracked.value
            if (tracked.compareAndSet(current, current + (storeName to taskId))) return
        }
    }

    private fun removeIfCurrent(
        storeName: String,
        taskId: TaskId,
    ) {
        while (true) {
            val current = tracked.value
            if (current[storeName] != taskId) return
            if (tracked.compareAndSet(current, current - storeName)) return
        }
    }
}
