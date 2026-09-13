package org.mobilenativefoundation.store6.mutations.drain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * Delay-based scheduler for hosts without an OS scheduler and for tests. Constraints are not
 * evaluated, and [validate] accepts every constraint set. Activations fire after their requested
 * delay regardless of constraints. When a host is offline, the transport attempt fails and the
 * coordinator reschedules the pass with its derived backoff, so repeated offline passes use
 * escalating delays instead of spinning.
 *
 * [schedule] throws [IllegalStateException] when [scope] is no longer active.
 */
@ExperimentalStoreApi
public class InProcessDrainScheduler(
    private val scope: CoroutineScope,
) : DrainScheduler {
    private val timers = MutableStateFlow<Map<String, Job>>(emptyMap())
    private var attached: MutationDrainCoordinator? = null

    @ExperimentalStoreApi
    override fun attach(coordinator: MutationDrainCoordinator): Unit {
        check(attached == null) { "InProcessDrainScheduler is already attached." }
        attached = coordinator
    }

    @ExperimentalStoreApi
    override fun validate(constraints: DrainConstraints): Unit = Unit

    @ExperimentalStoreApi
    override fun schedule(request: DrainRequest): Unit {
        val coordinator =
            checkNotNull(attached) { "InProcessDrainScheduler is not attached." }
        check(scope.isActive) { "InProcessDrainScheduler scope is not active." }

        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(request.earliestDelay)
                timers.removeIfCurrent(request.storeName, job)
                coordinator.runActivation(request.storeName)
            }
        val previous = timers.putReplacing(request.storeName, job)
        previous?.cancel()
        job.invokeOnCompletion {
            timers.removeIfCurrent(request.storeName, job)
        }
        job.start()
    }

    @ExperimentalStoreApi
    override fun cancel(storeName: String): Unit {
        checkNotNull(attached) { "InProcessDrainScheduler is not attached." }
        timers.remove(storeName)?.cancel()
    }

    private fun MutableStateFlow<Map<String, Job>>.putReplacing(
        storeName: String,
        job: Job,
    ): Job? {
        while (true) {
            val current = value
            val previous = current[storeName]
            if (compareAndSet(current, current + (storeName to job))) {
                return previous
            }
        }
    }

    private fun MutableStateFlow<Map<String, Job>>.remove(storeName: String): Job? {
        while (true) {
            val current = value
            val job = current[storeName] ?: return null
            if (compareAndSet(current, current - storeName)) {
                return job
            }
        }
    }

    private fun MutableStateFlow<Map<String, Job>>.removeIfCurrent(
        storeName: String,
        job: Job,
    ) {
        while (true) {
            val current = value
            if (current[storeName] !== job) return
            if (compareAndSet(current, current - storeName)) return
        }
    }
}
