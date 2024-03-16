package org.mobilenativefoundation.paging.core.impl

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.paging.core.PagingSource

class RetriesManager<Id : Comparable<Id>, K : Any, P : Any, D : Any> {
    private val retries = mutableMapOf<PagingSource.LoadParams<K, P>, Int>()

    private val mutexForRetries = Mutex()

    suspend fun resetRetriesFor(params: PagingSource.LoadParams<K, P>) {
        mutexForRetries.withLock { retries[params] = 0 }
    }

    suspend fun getRetriesFor(params: PagingSource.LoadParams<K, P>): Int {
        val count = mutexForRetries.withLock {
            retries[params] ?: 0
        }

        return count
    }

    suspend fun incrementRetriesFor(params: PagingSource.LoadParams<K, P>) {
        mutexForRetries.withLock {
            val prevCount = retries[params] ?: 0
            val nextCount = prevCount + 1
            retries[params] = nextCount
        }
    }
}