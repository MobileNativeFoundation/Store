package com.com.nytimes.suspendCache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The value we keep in guava's cache and it handles custom logic for store
 *  * not caching failures
 *  * not having concurrent fetches for the same key
 *  * maybe fresh?
 *  * deduplication
 */
internal class StoreRecord<K, V>(
        private val key: K,
        precomputedValue : V? = null,
        private val loader: Loader<K, V>
) {
    private var inFlight = Mutex(false)
    @Volatile
    private var _value: V? = precomputedValue

    fun cachedValue(): V? = _value

    suspend fun freshValue(): V {
        // first try to lock inflight request so that we can avoid get() from making a call
        // but if we failed to lock, just request w/o a lock.
        // we want fresh to be really fresh and we don't want it to wait for another request
        if (inFlight.tryLock()) {
            try {
                return internalDoLoadAndCache()
            } finally {
                inFlight.unlock()
            }
        } else {
            return inFlight.withLock {
                return internalDoLoadAndCache()
            }
        }
    }

    private inline suspend fun internalDoLoadAndCache(): V {
        return runCatching {
            loader(key)
        }.also {
            it.getOrNull()?.let {
                _value = it
            }
        }.getOrThrow()
    }

    suspend fun value(): V {
        val cached = _value
        if (cached != null) {
            return cached
        }
        return inFlight.withLock {
            _value?.let {
                return it
            } ?: internalDoLoadAndCache()
        }
    }
}
