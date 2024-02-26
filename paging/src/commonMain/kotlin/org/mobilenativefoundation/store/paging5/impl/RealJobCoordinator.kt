package org.mobilenativefoundation.store.paging5.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class RealJobCoordinator(
    private val scope: CoroutineScope
) : JobCoordinator {

    private val jobs: MutableMap<Any, Job> = mutableMapOf()

    override fun launchIfNotActive(key: Any, block: suspend CoroutineScope.() -> Unit) {
        if (jobs[key]?.isActive != true) {
            val job = scope.launch {
                block()
            }
            jobs[key] = job

            job.invokeOnCompletion {
                cancel(key)
            }
        }
    }

    override fun cancel(key: Any) {
        jobs[key]?.cancel()
        jobs.remove(key)
    }

    override fun cancelAll() {
        jobs.keys.forEach { cancel(it) }
    }

}