package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.cache5.CacheBuilder

/**
 * Intermediate data manager for a caching system supporting pagination.
 * Tracks keys for rapid data retrieval and modification.
 */
class PagingCacheAccessor<Id : Any, Collection : Identifiable.Collection<Id, Single>, Single : Identifiable.Single<Id>> {
    private val collections = CacheBuilder<StoreKey.Collection<Id>, Collection>().build()
    private val singles = CacheBuilder<StoreKey.Single<Id>, Single>().build()
    private val keys = mutableSetOf<StoreKey<Id>>()


    /**
     * Retrieves a collection of items from the cache using the provided key.
     */
    fun getCollection(key: StoreKey.Collection<Id>): Collection? = collections.getIfPresent(key)

    /**
     * Retrieves an individual item from the cache using the provided key.
     */
    fun getSingle(key: StoreKey.Single<Id>): Single? = singles.getIfPresent(key)

    /**
     * Stores a collection of items in the cache and updates the key set.
     */
    fun putCollection(key: StoreKey.Collection<Id>, collection: Collection) {
        collections.put(key, collection)
        keys.add(key)
    }

    /**
     * Stores an individual item in the cache and updates the key set.
     */
    fun putSingle(key: StoreKey.Single<Id>, single: Single) {
        singles.put(key, single)
        keys.add(key)
    }

    /**
     * Removes all cache entries and clears the key set.
     */
    fun invalidateAll() {
        collections.invalidateAll()
        singles.invalidateAll()
        keys.clear()
    }

    /**
     * Removes an individual item from the cache and updates the key set.
     */
    fun invalidateSingle(key: StoreKey.Single<Id>) {
        singles.invalidate(key)
        keys.remove(key)
    }

    /**
     * Removes a collection of items from the cache and updates the key set.
     */
    fun invalidateCollection(key: StoreKey.Collection<Id>) {
        collections.invalidate(key)
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
                    val single = singles.getIfPresent(key)
                    if (single != null) {
                        count++
                    }
                }

                is StoreKey.Collection<Id> -> {
                    val collection = collections.getIfPresent(key)
                    if (collection != null) {
                        count += collection.items.size
                    }
                }
            }
        }
        return count
    }
}