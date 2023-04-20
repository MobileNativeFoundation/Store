package org.mobilenativefoundation.store.cache5

class HybridCache<Key : Any, Output : Identifiable<Key>>(
    cacheBuilder: CacheBuilder<Key, Output>
) {

    private val listCacheBuilder = CacheBuilder<Key, List<Output>>().apply {
        expireAfterAccess(cacheBuilder.expireAfterAccess)
        expireAfterWrite(cacheBuilder.expireAfterWrite)

        if (cacheBuilder.maximumSize > 0) {
            maximumSize(cacheBuilder.maximumSize)
        }
        // TODO(): Support weigher
    }

    private val itemKeyToListKey = mutableMapOf<Key, Key>()

    private val itemCache: Cache<Key, Output> = cacheBuilder.build()

    private val listCache: Cache<Key, List<Output>> = listCacheBuilder.build()

    fun getItem(key: Key): Output? {
        return itemCache.getIfPresent(key)
    }

    fun putItem(key: Key, item: Output) {
        itemCache.put(key, item)

        val listKey = itemKeyToListKey[key]
        if (listKey != null) {
            val updatedList = listCache.getIfPresent(listKey)?.map { if (it.id == key) item else it }
            if (updatedList != null) {
                listCache.put(listKey, updatedList)
            }
        }
    }

    fun getList(key: Key): List<Output>? {
        return listCache.getIfPresent(key)
    }

    fun putList(key: Key, items: List<Output>) {
        listCache.put(key, items)
        items.forEach { item ->
            itemCache.put(item.id, item)
            itemKeyToListKey[item.id] = key
        }
    }

    fun invalidateItem(key: Key) {
        itemCache.invalidate(key)
    }

    fun invalidateList(key: Key) {
        val list = listCache.getIfPresent(key)
        list?.forEach { item ->
            invalidateItem(item.id)
        }
        listCache.invalidate(key)
    }

    fun invalidateAll() {
        listCache.invalidateAll()
        itemCache.invalidateAll()
    }
    fun size(): Long {
        return itemCache.size() + listCache.size()
    }
}

fun <Key : Any, Output : Identifiable<Key>> CacheBuilder<Key, Output>.asHybridCache() = HybridCache(this)
