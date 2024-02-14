package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.impl.RealMutablePagingBuffer

/**
 * A custom data structure for efficiently storing and accessing paging data.
 * @param Id The type of the identifier used for paging.
 * @param CK The type of the collection key used for paging.
 * @param SO The type of the single object stored in the paging data.
 */
@ExperimentalStoreApi
interface MutablePagingBuffer<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> :
    PagingBuffer<Id, CK, SO> {
    fun put(params: PagingSource.LoadParams<Id, CK>, page: PagingSource.LoadResult.Page<Id, CK, SO>)
}

@ExperimentalStoreApi
inline fun <Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> mutablePagingBuffer(
    maxSize: Int
): MutablePagingBuffer<Id, CK, SO> {
    return RealMutablePagingBuffer(maxSize)
}
