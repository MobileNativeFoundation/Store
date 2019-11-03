package com.nytimes.android.external.store4.impl

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Simple holder that can ref-count items by a given key.
 */
internal class RefCountedResource<Key, T>(
    private val create: suspend (Key) -> T,
    private val onRelease : (suspend (Key, T) -> Unit)? = null
) {
    private val items = mutableMapOf<Key, Item>()
    private val lock = Mutex()

    suspend fun acquire(key: Key) : T = lock.withLock {
        items.getOrPut(key) {
            Item(create(key))
        }.also {
            it.refCount ++
        }.value
    }

    suspend fun release(key: Key, value : T) = lock.withLock {
        val existing = items[key]
        check(existing != null && existing.value === value) {
            "inconsistent release, seems like $value was leaked or never acquired"
        }
        existing.refCount --
        if (existing.refCount < 1) {
            items.remove(key)
            onRelease?.invoke(key, value)
        }
    }

    // used in tests
    suspend fun size() = lock.withLock {
        items.size
    }

    private inner class Item(
        val value: T,
        var refCount: Int = 0
    )
}
