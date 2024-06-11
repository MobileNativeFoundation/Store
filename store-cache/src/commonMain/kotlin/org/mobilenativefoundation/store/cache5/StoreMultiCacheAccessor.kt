package org.mobilenativefoundation.store.cache5

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

/**
 * Responsible for managing and accessing cached data.
 * Provides functionality to retrieve, store, and invalidate single items and collections of items.
 * All operations are thread-safe, ensuring safe usage across multiple threads.
 *
 * The thread safety of this class is ensured through the use of synchronized blocks.
 * Synchronized blocks guarantee only one thread can execute any of the methods at a time.
 * This prevents concurrent modifications and ensures consistency of the data.
 *
 * @param Id The type of the identifier used for the data.
 * @param Collection The type of the data collection.
 * @param Single The type of the single data item.
 * @property singlesCache The cache used to store single data items.
 * @property collectionsCache The cache used to store collections of data items.
 */
class StoreMultiCacheAccessor<Id : Any, Collection : StoreData.Collection<Id, Single>, Single : StoreData.Single<Id>>(
    private val singlesCache: Cache<StoreKey.Single<Id>, Single>,
    private val collectionsCache: Cache<StoreKey.Collection<Id>, Collection>,
) : SynchronizedObject() {
    private val keys = mutableSetOf<StoreKey<Id>>()

    /**
     * Retrieves a collection of items from the cache using the provided key.
     *
     * This operation is thread-safe.
     *
     * @param key The key used to retrieve the collection.
     * @return The cached collection or null if it's not present.
     */
    fun getCollection(key: StoreKey.Collection<Id>): Collection? =
        synchronized(this) {
            collectionsCache.getIfPresent(key)
        }

    /**
     * Retrieves an individual item from the cache using the provided key.
     *
     * This operation is thread-safe.
     *
     * @param key The key used to retrieve the single item.
     * @return The cached single item or null if it's not present.
     */
    fun getSingle(key: StoreKey.Single<Id>): Single? =
        synchronized(this) {
            singlesCache.getIfPresent(key)
        }

    /**
     * Retrieves all items from the cache.
     *
     * This operation is thread-safe.
     */
    fun getAllPresent(): Map<StoreKey<Id>, Any> =
        synchronized(this) {
            val result = mutableMapOf<StoreKey<Id>, Any>()
            for (key in keys) {
                when (key) {
                    is StoreKey.Single<Id> -> {
                        val single = singlesCache.getIfPresent(key)
                        if (single != null) {
                            result[key] = single
                        }
                    }

                    is StoreKey.Collection<Id> -> {
                        val collection = collectionsCache.getIfPresent(key)
                        if (collection != null) {
                            result[key] = collection
                        }
                    }
                }
            }
            result
        }

    /**
     * Stores a collection of items in the cache and updates the key set.
     *
     * This operation is thread-safe.
     *
     * @param key The key associated with the collection.
     * @param collection The collection to be stored in the cache.
     */
    fun putCollection(
        key: StoreKey.Collection<Id>,
        collection: Collection,
    ) = synchronized(this) {
        collectionsCache.put(key, collection)
        keys.add(key)
    }

    /**
     * Stores an individual item in the cache and updates the key set.
     *
     * This operation is thread-safe.
     *
     * @param key The key associated with the single item.
     * @param single The single item to be stored in the cache.
     */
    fun putSingle(
        key: StoreKey.Single<Id>,
        single: Single,
    ) = synchronized(this) {
        singlesCache.put(key, single)
        keys.add(key)
    }

    /**
     * Removes all cache entries and clears the key set.
     *
     * This operation is thread-safe.
     */
    fun invalidateAll() =
        synchronized(this) {
            collectionsCache.invalidateAll()
            singlesCache.invalidateAll()
            keys.clear()
        }

    /**
     * Removes an individual item from the cache and updates the key set.
     *
     * This operation is thread-safe.
     *
     * @param key The key associated with the single item to be invalidated.
     */
    fun invalidateSingle(key: StoreKey.Single<Id>) =
        synchronized(this) {
            singlesCache.invalidate(key)
            keys.remove(key)
        }

    /**
     * Removes a collection of items from the cache and updates the key set.
     *
     * This operation is thread-safe.
     *
     * @param key The key associated with the collection to be invalidated.
     */
    fun invalidateCollection(key: StoreKey.Collection<Id>) =
        synchronized(this) {
            collectionsCache.invalidate(key)
            keys.remove(key)
        }

    /**
     * Calculates the total count of items in the cache, including both single items and items in collections.
     *
     * This operation is thread-safe.
     *
     * @return The total count of items in the cache.
     */
    fun size(): Long =
        synchronized(this) {
            var count = 0L
            for (key in keys) {
                when (key) {
                    is StoreKey.Single<Id> -> {
                        val single = singlesCache.getIfPresent(key)
                        if (single != null) {
                            count++
                        }
                    }

                    is StoreKey.Collection<Id> -> {
                        val collection = collectionsCache.getIfPresent(key)
                        if (collection != null) {
                            count += collection.items.size
                        }
                    }
                }
            }
            count
        }
}
