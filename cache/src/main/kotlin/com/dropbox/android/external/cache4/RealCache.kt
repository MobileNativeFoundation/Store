package com.dropbox.android.external.cache4

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * An implementation of [Cache] inspired by Guava Cache.
 *
 * Two types of evictions are supported:
 *
 * 1. Time-based evictions (expiration)
 * 2. Size-based evictions
 *
 * Time-based evictions are enabled by specifying [expireAfterWriteNanos] and/or [expireAfterAccessNanos].
 * When [expireAfterWriteNanos] is specified, entries will be automatically removed from the cache
 * once a fixed duration has elapsed after the entry's creation
 * or most recent replacement of its value.
 * When [expireAfterAccessNanos] is specified, entries will be automatically removed from the cache
 * once a fixed duration has elapsed after the entry's creation,
 * the most recent replacement of its value, or its last access.
 *
 * Note that creation and replacement of an entry is also considered an access.
 *
 * Size-based evictions are enabled by specifying [maxSize]. When the size of the cache entries grows
 * beyond [maxSize], least recently accessed entries will be evicted.
 *
 */
internal class RealCache<in Key, Value>(
    val expireAfterWriteNanos: Long,
    val expireAfterAccessNanos: Long,
    val maxSize: Long,
    val clock: Clock
) : Cache<Key, Value> {

    /**
     * A map holding the current cache entries.
     */
    private val cacheEntries: MutableMap<Key, CacheEntry<Key, Value>> = ConcurrentHashMap(
        INITIAL_CAPACITY, LOAD_FACTOR, CONCURRENCY_LEVEL
    )

    /**
     * A queue of unique cache entries ordered by write time.
     * Used for performing write-time based cache expiration.
     */
    private val writeQueue: MutableSet<CacheEntry<Key, Value>>?

    /**
     * A queue of unique cache entries ordered by access time.
     * Used for performing both write-time and read-time based cache expiration
     * as well as size-based eviction.
     *
     * Note that a write is also considered an access.
     */
    private val accessQueue: MutableSet<CacheEntry<Key, Value>>?

    /**
     * Whether to perform size based evictions.
     */
    private val evictsBySize = maxSize >= 0

    /**
     * Whether to perform write-time based expiration.
     */
    private val expiresAfterWrite = expireAfterWriteNanos > 0

    /**
     * Whether to perform access-time (both read and write) based expiration.
     */
    private val expiresAfterAccess = expireAfterAccessNanos > 0

    init {
        // writeQueue is required if write expiry is enabled
        writeQueue = takeIf { expiresAfterWrite }?.let {
            Collections.synchronizedSet(ReorderingLinkedHashSet())
        }

        // accessQueue is required if either read expiry is enabled or size-based eviction is enabled
        accessQueue = takeIf { expiresAfterAccess || evictsBySize }?.let {
            Collections.synchronizedSet(ReorderingLinkedHashSet())
        }
    }

    override fun get(key: Key): Value? {
        val nowNanos = clock.currentTimeNanos
        return cacheEntries[key]?.let {
            if (it.isExpired(nowNanos)) {
                // clean up expired entries and return null
                expireEntries(nowNanos)
                null
            } else {
                // update eviction metadata and return the value
                recordRead(it, nowNanos)
                it.value
            }
        }
    }

    override fun get(key: Key, loader: () -> Value): Value {
        synchronized(this) {
            val nowNanos = clock.currentTimeNanos
            return cacheEntries[key]?.let {
                if (it.isExpired(nowNanos)) {
                    // clean up expired entries
                    expireEntries(nowNanos)
                    null
                } else {
                    // update eviction metadata
                    recordRead(it, nowNanos)
                    it.value
                }
            } ?: loader().let { loadedValue ->
                // cache the loaded value if value for the key is still absent
                // from the cache entries after executing the loader
                val existingValue = get(key)
                if (existingValue != null) {
                    existingValue
                } else {
                    put(key, loadedValue)
                    loadedValue
                }
            }
        }
    }

    override fun put(key: Key, value: Value) {
        val nowNanos = clock.currentTimeNanos
        expireEntries(nowNanos)

        val existingEntry = cacheEntries[key]
        if (existingEntry != null) {
            // cache entry found
            recordWrite(existingEntry, nowNanos)
            existingEntry.value = value
            cacheEntries[key] = existingEntry
        } else {
            // create a new cache entry
            val newEntry = CacheEntry(key, value)
            recordWrite(newEntry, nowNanos)
            cacheEntries[key] = newEntry
        }

        evictEntries()
    }

    override fun invalidate(key: Key) {
        val nowNanos = clock.currentTimeNanos
        expireEntries(nowNanos)

        cacheEntries.remove(key)?.also {
            writeQueue?.remove(it)
            accessQueue?.remove(it)
        }
    }

    override fun invalidateAll() {
        cacheEntries.clear()
        writeQueue?.clear()
        accessQueue?.clear()
    }

    /**
     * Remove all expired entries.
     */
    private fun expireEntries(nowNanos: Long) {
        listOfNotNull(writeQueue, accessQueue).forEach { queue ->
            synchronized(queue) {
                val iterator = queue.iterator()
                for (entry in iterator) {
                    if (entry.isExpired(nowNanos)) {
                        cacheEntries.remove(entry.key)
                        // remove the entry from the current queue
                        iterator.remove()
                        // also remove the entry from the other queue
                        if (queue == writeQueue) {
                            accessQueue?.remove(entry)
                        } else {
                            writeQueue?.remove(entry)
                        }
                    } else {
                        // found unexpired entry, no need to look any further
                        break
                    }
                }
            }
        }
    }

    /**
     * Check whether the [CacheEntry] has expired based on either access time or write time.
     */
    private fun CacheEntry<Key, Value>.isExpired(nowNanos: Long): Boolean =
        (expiresAfterAccess && nowNanos - accessTimeNanos >= expireAfterAccessNanos) ||
            (expiresAfterWrite && nowNanos - writeTimeNanos >= expireAfterWriteNanos)

    /**
     * Evict least recently accessed entries until [cacheEntries] is no longer over capacity.
     */
    private fun evictEntries() {
        if (!evictsBySize) {
            return
        }

        while (cacheEntries.size > maxSize) {
            val entryToEvict = accessQueue!!.first()
            cacheEntries.remove(entryToEvict.key)
            writeQueue?.remove(entryToEvict)
            accessQueue.remove(entryToEvict)
        }
    }

    /**
     * Update the eviction metadata on the [cacheEntry] which has just been read.
     */
    private fun recordRead(cacheEntry: CacheEntry<Key, Value>, nowNanos: Long) {
        if (expiresAfterAccess) {
            cacheEntry.accessTimeNanos = nowNanos
        }
        accessQueue?.add(cacheEntry)
    }

    /**
     * Update the eviction metadata on the [CacheEntry] which is about to be written.
     * Note that a write is also considered an access.
     */
    private fun recordWrite(cacheEntry: CacheEntry<Key, Value>, nowNanos: Long) {
        if (expiresAfterAccess) {
            cacheEntry.accessTimeNanos = nowNanos
        }
        if (expiresAfterWrite) {
            cacheEntry.writeTimeNanos = nowNanos
        }
        accessQueue?.add(cacheEntry)
        writeQueue?.add(cacheEntry)
    }

    companion object {
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
        private const val CONCURRENCY_LEVEL = 4
    }
}
