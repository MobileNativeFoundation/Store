package org.mobilenativefoundation.store.paging5.impl

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.PagingSource

@ExperimentalStoreApi
class RetriesRepository<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> {
    private val retries = mutableMapOf<PagingSource.LoadParams<Id, CK>, Int>()

    private val mutexForRetries = Mutex()

    suspend fun resetRetriesFor(params: PagingSource.LoadParams<Id, CK>) {
        mutexForRetries.withLock { retries[params] = 0 }
    }

    suspend fun getRetriesFor(params: PagingSource.LoadParams<Id, CK>): Int {
        val count = mutexForRetries.withLock {
            retries[params] ?: 0
        }

        return count
    }

    suspend fun incrementRetriesFor(params: PagingSource.LoadParams<Id, CK>) {
        mutexForRetries.withLock {
            val prevCount = retries[params] ?: 0
            val nextCount = prevCount + 1
            retries[params] = nextCount
        }
    }
}
