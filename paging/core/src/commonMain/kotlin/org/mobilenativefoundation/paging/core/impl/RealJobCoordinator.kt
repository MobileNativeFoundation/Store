package org.mobilenativefoundation.paging.core.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RealJobCoordinator(
    private val childScope: CoroutineScope
) : JobCoordinator {
    private val jobs: MutableMap<Any, Job> = mutableMapOf()

    override fun launch(
        key: Any,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        cancel(key)

        val job =
            childScope.launch {
                block()
            }
        jobs[key] = job

        job.invokeOnCompletion {
            job.cancel()
        }
    }

    override fun launchIfNotActive(
        key: Any,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        if (jobs[key]?.isActive != true) {
            launch(key, block)
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