package org.mobilenativefoundation.store6.paging

import androidx.paging.LoadType
import androidx.paging.PagingState
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreKey

internal class StorePagingConfig<K : StoreKey, V : Any, PK : Any, Item : Any>(
    val pageKey: (paginationKey: PK?, loadSize: Int) -> K,
    val items: (V) -> List<Item>,
    val nextKey: (K, V) -> PK?,
    val prevKey: (K, V) -> PK?,
    val freshness: (LoadType) -> Freshness,
    val refreshKey: (PagingState<PK, Item>) -> PK?,
    val itemsBefore: (K, V) -> Int,
    val itemsAfter: (K, V) -> Int,
)
