package org.mobilenativefoundation.store.cache5

import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

/**
 * Intermediate data manager for a caching system supporting list decomposition.
 * Tracks keys for rapid data retrieval and modification.
 */
class StoreMultiCacheAccessor<Id : Any, Collection : StoreData.Collection<Id, Single>, Single : StoreData.Single<Id>>(
    private val singlesCache: Cache<StoreKey.Single<Id>, Single>,
    private val collectionsCache: Cache<StoreKey.Collection<Id>, Collection>,
) {
    private val keys = mutableSetOf<StoreKey<Id>>()


    /**
     * Retrieves a collection of items from the cache using the provided key.
     */
    fun getCollection(key: StoreKey.Collection<Id>): Collection? = collectionsCache.getIfPresent(key)

    /**
     * Retrieves an individual item from the cache using the provided key.
     */
    fun getSingle(key: StoreKey.Single<Id>): Single? = singlesCache.getIfPresent(key)

    /**
     * Stores a collection of items in the cache and updates the key set.
     */
    fun putCollection(key: StoreKey.Collection<Id>, collection: Collection) {
        collectionsCache.put(key, collection)
        keys.add(key)
    }

    /**
     * Stores an individual item in the cache and updates the key set.
     */
    fun putSingle(key: StoreKey.Single<Id>, single: Single) {
        singlesCache.put(key, single)
        keys.add(key)
    }

    /**
     * Removes all cache entries and clears the key set.
     */
    fun invalidateAll() {
        collectionsCache.invalidateAll()
        singlesCache.invalidateAll()
        keys.clear()
    }

    /**
     * Removes an individual item from the cache and updates the key set.
     */
    fun invalidateSingle(key: StoreKey.Single<Id>) {
        singlesCache.invalidate(key)
        keys.remove(key)
    }

    /**
     * Removes a collection of items from the cache and updates the key set.
     */
    fun invalidateCollection(key: StoreKey.Collection<Id>) {
        collectionsCache.invalidate(key)
        keys.remove(key)
    }

    /**
     * Calculates the total count of items in the cache.
     * Includes individual items as well as items in collections.
     */

    fun size(): Long {
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
        return count
    }
}