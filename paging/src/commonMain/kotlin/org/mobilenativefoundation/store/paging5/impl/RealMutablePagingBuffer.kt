package org.mobilenativefoundation.store.paging5.impl

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.MutablePagingBuffer
import org.mobilenativefoundation.store.paging5.PagingSource

/**
 * The [RealMutablePagingBuffer] class provides an optimized solution for storing and retrieving paging data.
 * It maintains an array-based buffer of fixed size to store the paging data entries, along with
 * hash maps to map the load parameters and keys to the corresponding index in the buffer.
 *
 * The buffer follows a circular queue approach, where the oldest entry is replaced when the buffer
 * reaches its maximum capacity. This ensures a fixed memory footprint while efficiently handling
 * the addition and retrieval of paging data entries.
 */
@ExperimentalStoreApi
class RealMutablePagingBuffer<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>>(
    private val maxSize: Int
) : MutablePagingBuffer<Id, CK, SO> {
    private val buffer: Array<PagingSource.LoadResult.Page<Id, CK, SO>?> = arrayOfNulls(maxSize)
    private val paramsToIndex: MutableMap<PagingSource.LoadParams<Id, CK>, Int> = mutableMapOf()
    private val keyToIndex: MutableMap<CK, Int> = mutableMapOf()
    private var head = 0
    private var tail = 0
    private var size = 0

    override fun put(params: PagingSource.LoadParams<Id, CK>, page: PagingSource.LoadResult.Page<Id, CK, SO>) {
        if (size == maxSize) {
            val oldestIndex = head
            val oldestParams = paramsToIndex.entries.first { it.value == oldestIndex }.key
            paramsToIndex.remove(oldestParams)
            keyToIndex.remove(oldestParams.key)
            buffer[oldestIndex] = null
            head = (head + 1) % maxSize
        }
        val index = tail
        buffer[index] = page
        paramsToIndex[params] = index
        keyToIndex[params.key] = index
        tail = (tail + 1) % maxSize
        size = minOf(size + 1, maxSize)
    }

    override fun get(params: PagingSource.LoadParams<Id, CK>): PagingSource.LoadResult.Page<Id, CK, SO>? {
        val index = paramsToIndex[params]
        return if (index != null) buffer[index] else null
    }

    override fun get(key: CK): PagingSource.LoadResult.Page<Id, CK, SO>? {
        val index = keyToIndex[key]
        return if (index != null) buffer[index] else null
    }

    override fun head(): PagingSource.LoadResult.Page<Id, CK, SO>? {
        return buffer[head]
    }

    override fun getAll(): List<PagingSource.LoadResult.Page<Id, CK, SO>> {
        val pages = mutableListOf<PagingSource.LoadResult.Page<Id, CK, SO>>()
        var index = head
        var count = 0
        while (count < size) {
            val page = buffer[index]
            if (page != null) {
                pages.add(page)
            }
            index = (index + 1) % maxSize
            count++
        }
        return pages
    }

    override fun isEmpty(): Boolean {
        return size == 0
    }

    override fun indexOf(id: Id): Int {
        return buffer.filterNotNull().flatMap { it.data }.indexOfFirst { it.id == id }
    }
}
