package org.mobilenativefoundation.store.paging5.impl

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi

@ExperimentalStoreApi
internal interface PagingErrorManager {
    fun handleError(error: Throwable)
}


