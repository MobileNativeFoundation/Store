package org.mobilenativefoundation.store.paging5.impl

import kotlinx.coroutines.CoroutineScope

internal interface JobCoordinator {
    fun launchIfNotActive(key: Any, block: suspend CoroutineScope.() -> Unit)
    fun cancel(key: Any)
    fun cancelAll()
}