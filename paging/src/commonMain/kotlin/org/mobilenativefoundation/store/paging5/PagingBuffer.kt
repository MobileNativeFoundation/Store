package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

/**
 * A custom data structure for efficiently accessing paging data.
 * @param Id The type of the identifier used for paging.
 * @param CK The type of the collection key used for paging.
 * @param SO The type of the single object stored in the paging data.
 */
@ExperimentalStoreApi
interface PagingBuffer<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> {
    fun get(params: PagingSource.LoadParams<Id, CK>): PagingSource.LoadResult.Page<Id, CK, SO>?

    fun get(key: CK): PagingSource.LoadResult.Page<Id, CK, SO>?

    fun head(): PagingSource.LoadResult.Page<Id, CK, SO>?

    fun getAll(): List<PagingSource.LoadResult.Page<Id, CK, SO>>

    fun isEmpty(): Boolean

    fun indexOf(id: Id): Int
}
