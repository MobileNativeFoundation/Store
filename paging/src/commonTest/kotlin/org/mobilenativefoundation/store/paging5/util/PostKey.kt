package org.mobilenativefoundation.store.paging5.util

import org.mobilenativefoundation.store.paging5.StoreKey

sealed class PostKey : StoreKey<String> {
    data class Cursor(
        override val cursor: String?,
        override val size: Int,
        override val sort: StoreKey.Sort? = null,
        override val filters: List<StoreKey.Filter<*>>? = null,
        override val loadType: StoreKey.LoadType = StoreKey.LoadType.PREPEND
    ) : StoreKey.Collection.Cursor<String>, PostKey()

    data class Single(
        override val id: String
    ) : StoreKey.Single<String>, PostKey()

}
