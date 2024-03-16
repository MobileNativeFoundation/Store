@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.cache5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.KeyProvider
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

/**
 * A class that represents a caching system with collection decomposition.
 * Manages data with utility functions to get, invalidate, and add items to the cache.
 * Depends on [StoreMultiCacheAccessor] for internal data management.
 * @see [Cache].
 */
@ExperimentalStoreApi
class StoreMultiCache<Id : Any, K : StoreKey<Id>, S : StoreData.Single<Id>, C : StoreData.Collection<Id, S>, O : StoreData<Id>>(
    private val keyProvider: KeyProvider<Id, S>,
    singlesCache: Cache<StoreKey.Single<Id>, S> = CacheBuilder<StoreKey.Single<Id>, S>().build(),
    collectionsCache: Cache<StoreKey.Collection<Id>, C> = CacheBuilder<StoreKey.Collection<Id>, C>().build(),
) : Cache<K, O> {
    private val accessor =
        StoreMultiCacheAccessor(
            singlesCache = singlesCache,
            collectionsCache = collectionsCache,
        )

    private fun K.castSingle() = this as StoreKey.Single<Id>

    private fun K.castCollection() = this as StoreKey.Collection<Id>

    private fun StoreKey.Collection<Id>.cast() = this as K

    private fun StoreKey.Single<Id>.cast() = this as K

    override fun getIfPresent(key: K): O? {
        return when (key) {
            is StoreKey.Single<*> -> accessor.getSingle(key.castSingle()) as? O
            is StoreKey.Collection<*> -> accessor.getCollection(key.castCollection()) as? O
            else -> {
                throw UnsupportedOperationException(invalidKeyErrorMessage(key))
            }
        }
    }

    override fun getOrPut(
        key: K,
        valueProducer: () -> O,
    ): O {
        return when (key) {
            is StoreKey.Single<*> -> {
                val single = accessor.getSingle(key.castSingle()) as? O
                if (single != null) {
                    single
                } else {
                    val producedSingle = valueProducer()
                    put(key, producedSingle)
                    producedSingle
                }
            }

            is StoreKey.Collection<*> -> {
                val collection = accessor.getCollection(key.castCollection()) as? O
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

    override fun getAllPresent(keys: List<*>): Map<K, O> {
        val map = mutableMapOf<K, O>()
        keys.filterIsInstance<StoreKey<Id>>().forEach { key ->
            when (key) {
                is StoreKey.Collection<Id> -> {
                    val collection = accessor.getCollection(key)
                    collection?.let { map[key.cast()] = it as O }
                }

                is StoreKey.Single<Id> -> {
                    val single = accessor.getSingle(key)
                    single?.let { map[key.cast()] = it as O }
                }
            }
        }

        return map
    }

    override fun invalidateAll(keys: List<K>) {
        keys.forEach { key -> invalidate(key) }
    }

    override fun invalidate(key: K) {
        when (key) {
            is StoreKey.Single<*> -> accessor.invalidateSingle(key.castSingle())
            is StoreKey.Collection<*> -> accessor.invalidateCollection(key.castCollection())
        }
    }

    override fun putAll(map: Map<K, O>) {
        map.entries.forEach { (key, value) -> put(key, value) }
    }

    override fun put(
        key: K,
        value: O,
    ) {
        when (key) {
            is StoreKey.Single<*> -> {
                val single = value as S
                accessor.putSingle(key.castSingle(), single)

                val collectionKey = keyProvider.fromSingle(key.castSingle(), single)
                val existingCollection = accessor.getCollection(collectionKey)
                if (existingCollection != null) {
                    val updatedItems =
                        existingCollection.items.toMutableList().map {
                            if (it.id == single.id) {
                                single
                            } else {
                                it
                            }
                        }
                    val updatedCollection = existingCollection.copyWith(items = updatedItems) as C
                    accessor.putCollection(collectionKey, updatedCollection)
                }
            }

            is StoreKey.Collection<*> -> {
                val collection = value as C
                accessor.putCollection(key.castCollection(), collection)

                collection.items.forEach {
                    val single = it as? S
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
        fun invalidKeyErrorMessage(key: Any) = "Expected StoreKey.Single or StoreKey.Collection, but received ${key::class}"
    }
}
