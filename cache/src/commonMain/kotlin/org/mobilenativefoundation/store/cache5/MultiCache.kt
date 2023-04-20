@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.cache5

/**
 * Implementation of a cache with collection decomposition.
 * Stores and manages the relationship among single items and collections.
 * Delegates cache storage and behavior to Guava caches.
 */
class MultiCache<Key : Any, Output : Identifiable<Key>>(
    cacheBuilder: CacheBuilder<Key, Output>
) {
    private val collectionCacheBuilder = CacheBuilder<Key, Collection<Output>>().apply {
        expireAfterAccess(cacheBuilder.expireAfterAccess)
        expireAfterWrite(cacheBuilder.expireAfterWrite)

        if (cacheBuilder.maximumSize > 0) {
            maximumSize(cacheBuilder.maximumSize)
        }
        // TODO(): Support weigher
    }

    private val itemKeyToCollectionKey = mutableMapOf<Key, Key>()

    private val itemCache: Cache<Key, Output> = cacheBuilder.build()

    private val collectionCache: Cache<Key, Collection<Output>> = collectionCacheBuilder.build()

    fun getItem(key: Key): Output? {
        return itemCache.getIfPresent(key)
    }

    fun putItem(key: Key, item: Output) {
        itemCache.put(key, item)

        val collectionKey = itemKeyToCollectionKey[key]
        if (collectionKey != null) {
            val updatedCollection = collectionCache.getIfPresent(collectionKey)?.map { if (it.id == key) item else it }
            if (updatedCollection != null) {
                collectionCache.put(collectionKey, updatedCollection)
            }
        }
    }

    fun <T : Collection<Output>> getCollection(key: Key): T? {
        return collectionCache.getIfPresent(key) as? T
    }

    fun putCollection(key: Key, items: Collection<Output>) {
        collectionCache.put(key, items)
        items.forEach { item ->
            itemCache.put(item.id, item)
            itemKeyToCollectionKey[item.id] = key
        }
    }

    fun invalidateItem(key: Key) {
        itemCache.invalidate(key)
    }

    fun invalidateCollection(key: Key) {
        val collection = collectionCache.getIfPresent(key)
        collection?.forEach { item ->
            invalidateItem(item.id)
        }
        collectionCache.invalidate(key)
    }

    fun invalidateAll() {
        collectionCache.invalidateAll()
        itemCache.invalidateAll()
    }

    fun size(): Long {
        return itemCache.size() + collectionCache.size()
    }
}
