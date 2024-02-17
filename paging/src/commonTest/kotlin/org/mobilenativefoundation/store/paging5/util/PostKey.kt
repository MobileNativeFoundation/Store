package org.mobilenativefoundation.store.paging5.util

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.InsertionStrategy
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.KeyFactory

@OptIn(ExperimentalStoreApi::class)
sealed class PostKey : StoreKey<String> {
    data class Cursor(
        override val cursor: String?,
        override val size: Int,
        override val sort: StoreKey.Sort? = null,
        override val filters: List<StoreKey.Filter<*>>? = null,
        override val insertionStrategy: InsertionStrategy = InsertionStrategy.APPEND
    ) : StoreKey.Collection.Cursor<String>, PostKey()

    data class Single(
        override val id: String
    ) : StoreKey.Single<String>, PostKey()
}


@OptIn(ExperimentalStoreApi::class)
class PostKeyFactory : KeyFactory<String, PostKey.Single> {
    override fun createFor(id: String): PostKey.Single {
        return PostKey.Single(id)
    }
}