package org.mobilenativefoundation.paging.core.impl

import kotlinx.coroutines.CoroutineScope

interface JobCoordinator {
    fun launch(key: Any, block: suspend CoroutineScope.() -> Unit)
    fun launchIfNotActive(key: Any, block: suspend CoroutineScope.() -> Unit)
    fun cancel(key: Any)
    fun cancelAll()
}
