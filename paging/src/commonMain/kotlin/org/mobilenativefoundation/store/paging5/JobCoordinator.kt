package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.CoroutineScope

interface JobCoordinator {
    fun launchIfNotActive(
        key: Any,
        block: suspend CoroutineScope.() -> Unit,
    )

    fun cancel(key: Any)

    fun cancelAll()
}
