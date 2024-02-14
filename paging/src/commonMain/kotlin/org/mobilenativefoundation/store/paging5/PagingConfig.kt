package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi

@ExperimentalStoreApi
data class PagingConfig(
    val pageSize: Int = 10,
    val prefetchDistance: Int = 100,
)
