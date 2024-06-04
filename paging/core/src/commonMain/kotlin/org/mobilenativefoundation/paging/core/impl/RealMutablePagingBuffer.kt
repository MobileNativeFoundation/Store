package org.mobilenativefoundation.paging.core.impl

import org.mobilenativefoundation.paging.core.MutablePagingBuffer
import org.mobilenativefoundation.paging.core.PagingKey
import org.mobilenativefoundation.paging.core.PagingSource

/**
 * A concrete implementation of [MutablePagingBuffer], a custom data structure for efficiently storing and retrieving paging data.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched to modify the paging state.
 * @property maxSize The maximum size of the buffer.
 */
class RealMutablePagingBuffer<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any>(
    private val maxSize: Int
) : MutablePagingBuffer<Id, K, P, D> {

    private val buffer: Array<PagingSource.LoadResult.Data<Id, K, P, D>?> = arrayOfNulls(maxSize)
    private val paramsToIndex: MutableMap<PagingSource.LoadParams<K, P>, Int> = mutableMapOf()
    private val keyToIndex: MutableMap<PagingKey<K, P>, Int> = mutableMapOf()
    private var head = 0
    private var tail = 0
    private var size = 0

    override fun get(params: PagingSource.LoadParams<K, P>): PagingSource.LoadResult.Data<Id, K, P, D>? {
        val index = paramsToIndex[params]
        return if (index != null) buffer[index] else null
    }


    override fun get(key: PagingKey<K, P>): PagingSource.LoadResult.Data<Id, K, P, D>? {
        val index = keyToIndex[key]
        return if (index != null) buffer[index] else null
    }


    override fun put(params: PagingSource.LoadParams<K, P>, page: PagingSource.LoadResult.Data<Id, K, P, D>) {
        // Check if the buffer is full
        if (size == maxSize) {
            // Get the index of the oldest entry in the buffer
            val oldestIndex = head
            // Find the params associated with the oldest entry
            val oldestParams = paramsToIndex.entries.first { it.value == oldestIndex }.key
            // Remove the oldest entry from the maps
            paramsToIndex.remove(oldestParams)
            keyToIndex.remove(oldestParams.key)
            // Remove the oldest entry from the buffer
            buffer[oldestIndex] = null
            // Update the head index to point to the next entry
            head = (head + 1) % maxSize
        }
        // Get the index to insert the new page
        val index = tail
        // Insert the new page at the tail index
        buffer[index] = page
        // Update the maps with the new entry
        paramsToIndex[params] = index
        keyToIndex[params.key] = index
        // Update the tail index to point to the next empty slot
        tail = (tail + 1) % maxSize
        // Update the size of the buffer
        size = minOf(size + 1, maxSize)
    }

    override fun head(): PagingSource.LoadResult.Data<Id, K, P, D>? {
        return buffer[head]
    }

    override fun getAll(): List<PagingSource.LoadResult.Data<Id, K, P, D>> {
        val pages = mutableListOf<PagingSource.LoadResult.Data<Id, K, P, D>>()
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

    override fun isEmpty(): Boolean = size == 0

    override fun indexOf(key: PagingKey<K, P>): Int {
        return keyToIndex[key] ?: -1
    }
}
