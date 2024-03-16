package org.mobilenativefoundation.paging.core.impl

import org.mobilenativefoundation.paging.core.PagingKey

/**
 * Represents a manager for the queue of pages to be loaded.
 *
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 */
interface QueueManager<K : Any, P : Any> {
    /**
     * Enqueues a page key to be loaded.
     *
     * @param key The [PagingKey] representing the page to be loaded.
     */
    fun enqueue(key: PagingKey<K, P>)
}
