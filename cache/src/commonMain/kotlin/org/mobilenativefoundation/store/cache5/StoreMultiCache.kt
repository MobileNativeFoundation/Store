@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.cache5

import org.mobilenativefoundation.store.core5.KeyProvider
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

/**
 * A class that represents a caching system with collection decomposition.
 * Manages data with utility functions to get, invalidate, and add items to the cache.
 * Depends on [StoreMultiCacheAccessor] for internal data management.
 * @see [Cache].
 */
class StoreMultiCache<Id : Any, Key : StoreKey<Id>, Single : StoreData.Single<Id>, Collection : StoreData.Collection<Id, Single>, Output : StoreData<Id>>(
    private val keyProvider: KeyProvider<Id, Single>,
    singlesCache: Cache<StoreKey.Single<Id>, Single> = CacheBuilder<StoreKey.Single<Id>, Single>().build(),
    collectionsCache: Cache<StoreKey.Collection<Id>, Collection> = CacheBuilder<StoreKey.Collection<Id>, Collection>().build(),
) : Cache<Key, Output> {

    private val accessor = StoreMultiCacheAccessor(
        singlesCache = singlesCache,
        collectionsCache = collectionsCache,
    )

    private fun Key.castSingle() = this as StoreKey.Single<Id>
    private fun Key.castCollection() = this as StoreKey.Collection<Id>

    private fun StoreKey.Collection<Id>.cast() = this as Key
    private fun StoreKey.Single<Id>.cast() = this as Key

    override fun getIfPresent(key: Key): Output? {
        return when (key) {
            is StoreKey.Single<*> -> accessor.getSingle(key.castSingle()) as? Output
            is StoreKey.Collection<*> -> accessor.getCollection(key.castCollection()) as? Output
            else -> {
                throw UnsupportedOperationException(invalidKeyErrorMessage(key))
            }
        }
    }

    override fun getOrPut(key: Key, valueProducer: () -> Output): Output {
        return when (key) {
            is StoreKey.Single<*> -> {
                val single = accessor.getSingle(key.castSingle()) as? Output
                if (single != null) {
                    single
                } else {
                    val producedSingle = valueProducer()
                    put(key, producedSingle)
                    producedSingle
                }
            }

            is StoreKey.Collection<*> -> {
                val collection = accessor.getCollection(key.castCollection()) as? Output
                if (collection != null) {
                    collection
                } else {
                    val producedCollection = valueProducer()
                    put(key, producedCollection)
                    producedCollection
                }
            }

            else -> {
                throw UnsupportedOperationException(invalidKeyErrorMessage(key))
            }
        }
    }

    override fun getAllPresent(keys: List<*>): Map<Key, Output> {
        val map = mutableMapOf<Key, Output>()
        keys.filterIsInstance<StoreKey<Id>>().forEach { key ->
            when (key) {
                is StoreKey.Collection<Id> -> {
                    val collection = accessor.getCollection(key)
                    collection?.let { map[key.cast()] = it as Output }
                }

                is StoreKey.Single<Id> -> {
                    val single = accessor.getSingle(key)
                    single?.let { map[key.cast()] = it as Output }
                }
            }
        }

        return map
    }

    override fun invalidateAll(keys: List<Key>) {
        keys.forEach { key -> invalidate(key) }
    }

    override fun invalidate(key: Key) {
        when (key) {
            is StoreKey.Single<*> -> accessor.invalidateSingle(key.castSingle())
            is StoreKey.Collection<*> -> accessor.invalidateCollection(key.castCollection())
        }
    }

    override fun putAll(map: Map<Key, Output>) {
        map.entries.forEach { (key, value) -> put(key, value) }
    }

    override fun put(key: Key, value: Output) {
        when (key) {
            is StoreKey.Single<*> -> {
                val single = value as Single
                accessor.putSingle(key.castSingle(), single)

                val collectionKey = keyProvider.fromSingle(key.castSingle(), single)
                val existingCollection = accessor.getCollection(collectionKey)
                if (existingCollection != null) {
                    val updatedItems = existingCollection.items.toMutableList().map {
                        if (it.id == single.id) {
                            single
                        } else {
                            it
                        }
                    }
                    val updatedCollection = existingCollection.copyWith(items = updatedItems) as Collection
                    accessor.putCollection(collectionKey, updatedCollection)
                }
            }

            is StoreKey.Collection<*> -> {
                val collection = value as Collection
                accessor.putCollection(key.castCollection(), collection)

                collection.items.forEach {
                    val single = it as? Single
                    if (single != null) {
                        accessor.putSingle(keyProvider.fromCollection(key.castCollection(), single), single)
                    }
                }
            }
        }
    }

    override fun invalidateAll() {
        accessor.invalidateAll()
    }

    override fun size(): Long {
        return accessor.size()
    }

    companion object {
        fun invalidKeyErrorMessage(key: Any) =
            "Expected StoreKey.Single or StoreKey.Collection, but received ${key::class}"
    }
}
