@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain

internal class RecordingDrainScheduler : DrainScheduler {
    internal val log: MutableList<String> = mutableListOf()
    internal val validated: MutableList<DrainConstraints> = mutableListOf()
    internal val scheduled: MutableList<DrainRequest> = mutableListOf()
    internal val cancelled: MutableList<String> = mutableListOf()
    internal var scheduleThrows: (() -> Throwable)? = null
    internal var scheduleThrowsOnCall: Int? = null
    internal var validateThrows: Throwable? = null

    private var scheduleCalls = 0
    private var coordinator: MutationDrainCoordinator? = null

    override fun attach(coordinator: MutationDrainCoordinator) {
        check(this.coordinator == null) { "RecordingDrainScheduler is already attached." }
        this.coordinator = coordinator
    }

    override fun validate(constraints: DrainConstraints) {
        log +=
            "validate(network=${constraints.requiresNetwork}, " +
                "charging=${constraints.requiresCharging})"
        validated += constraints
        validateThrows?.let { throw it }
    }

    override fun schedule(request: DrainRequest) {
        check(coordinator != null) { "RecordingDrainScheduler is not attached." }
        scheduleCalls += 1
        log += "schedule(${request.storeName}, ${request.earliestDelay})"
        scheduled += request
        val failure = scheduleThrows
        if (
            failure != null &&
            (scheduleThrowsOnCall == null || scheduleThrowsOnCall == scheduleCalls)
        ) {
            throw failure()
        }
    }

    override fun cancel(storeName: String) {
        check(coordinator != null) { "RecordingDrainScheduler is not attached." }
        log += "cancel($storeName)"
        cancelled += storeName
    }

    internal suspend fun fireActivation(name: String): DrainPassOutcome =
        checkNotNull(coordinator) { "RecordingDrainScheduler is not attached." }
            .runActivation(name)
}
