package com.dropbox.android.external.cache4

import com.dropbox.android.external.cache4.CacheBuilderImpl.Companion.UNSET_LONG
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.nanoseconds

/**
 * An implementation of [Cache] inspired by Guava Cache.
 *
 * Two types of evictions are supported:
 *
 * 1. Time-based evictions (expiration)
 * 2. Size-based evictions
 *
 * Time-based evictions are enabled by specifying [expireAfterWriteDuration] and/or [expireAfterAccessDuration].
 * When [expireAfterWriteDuration] is specified, entries will be automatically removed from the cache
 * once a fixed duration has elapsed after the entry's creation
 * or most recent replacement of its value.
 * When [expireAfterAccessDuration] is specified, entries will be automatically removed from the cache
 * once a fixed duration has elapsed after the entry's creation,
 * the most recent replacement of its value, or its last access.
 *
 * Note that creation and replacement of an entry is also considered an access.
 *
 * Size-based evictions are enabled by specifying [maxSize]. When the size of the cache entries grows
 * beyond [maxSize], least recently accessed entries will be evicted.
 */
@ExperimentalTime
internal class RealCache<Key : Any, Value : Any>(
    val expireAfterWriteDuration: Duration,
    val expireAfterAccessDuration: Duration,
    val maxSize: Long,
    val concurrencyLevel: Int,
    val clock: Clock,
    val weigher: Weigher<Key, Value> = OneWeigher(),
    val maxWeight: Long = UNSET_LONG
) : Cache<Key, Value> {

    /**
     * A map holding the current cache entries.
     */
    private val cacheEntries: MutableMap<Key, CacheEntry<Key, Value>> = ConcurrentHashMap(
        INITIAL_CAPACITY, LOAD_FACTOR, concurrencyLevel
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
    private val expiresAfterWrite = expireAfterWriteDuration.isFinite()

    /**
     * Whether to perform access-time (both read and write) based expiration.
     */
    private val expiresAfterAccess = expireAfterAccessDuration.isFinite()

    /**
     * A key-based synchronizer for running cache loaders.
     */
    private val loadersSynchronizer = KeyedSynchronizer<Key>()

    private val totalWeight = atomic(0)

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
                // update eviction metadata
                recordRead(it, nowNanos)
                it.value
            }
        }
    }

    override fun get(key: Key, loader: () -> Value): Value {
        return loadersSynchronizer.synchronizedFor(key) {
            val nowNanos = clock.currentTimeNanos
            cacheEntries[key]?.let {
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
            val weight = weigher.weigh(key, value)
            recordWrite(existingEntry, nowNanos, weight)

            existingEntry.value = value
            existingEntry.weight = weight
        } else {
            // create a new cache entry
            val weight = weigher.weigh(key, value)
            val newEntry = CacheEntry(key, value, weight)
            recordWrite(newEntry, nowNanos, weight)
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
            totalWeight -= it.weight
        }
    }

    override fun invalidateAll() {
        cacheEntries.clear()
        writeQueue?.clear()
        accessQueue?.clear()
        totalWeight = 0
    }

    override fun asMap(): Map<in Key, Value> {
        return cacheEntries.mapValues { (_, entry) -> entry.value }
    }

    /**
     * Remove all expired entries.
     */
    private fun expireEntries(nowNanos: Long) {
        val queuesToProcess = listOfNotNull(
            if (expiresAfterWrite) writeQueue else null,
            if (expiresAfterAccess) accessQueue else null
        )

        // address any inconsistencies between the queues and map before processing.
        if (queuesToProcess.isNotEmpty()) {
            cleanUpDeadEntries()
        }

        queuesToProcess.forEach { queue ->
            synchronized(queue) {
                val iterator = queue.iterator()
                for (entry in iterator) {
                    if (entry.isExpired(nowNanos)) {
                        cacheEntries.remove(entry.key)
                        //TODO MIKE do we need to subtract here?
                        totalWeight -= entry.weight
                        // remove the entry from the current queue
                        iterator.remove()
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
        (expiresAfterAccess && (nowNanos - accessTimeNanos).nanoseconds >= expireAfterAccessDuration) ||
            (expiresAfterWrite && (nowNanos - writeTimeNanos).nanoseconds >= expireAfterWriteDuration)

    /**
     * Evict least recently accessed entries until [cacheEntries] is no longer over capacity.
     */
    private fun evictEntries() {
        if (!evictsBySize) {
            return
        }

        checkNotNull(accessQueue)

        // address any inconsistencies between the queues and map before eviction.
        cleanUpDeadEntries()

        while (totalWeight > maxWeight) {
            val entryToEvict = synchronized(accessQueue) { accessQueue.firstOrNull() }
            entryToEvict?.run {
                cacheEntries.remove(entryToEvict.key)
                writeQueue?.remove(entryToEvict)
                accessQueue.remove(entryToEvict)
                totalWeight -= entryToEvict.weight
            }
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
    private fun recordWrite(
        cacheEntry: CacheEntry<Key, Value>,
        nowNanos: Long,
        weight: Int
    ) {
        totalWeight += weight
        if (expiresAfterAccess) {
            cacheEntry.accessTimeNanos = nowNanos
        }
        if (expiresAfterWrite) {
            cacheEntry.writeTimeNanos = nowNanos
        }
        accessQueue?.add(cacheEntry)
        writeQueue?.add(cacheEntry)
    }

    /**
     * Although [cacheEntries], [writeQueue], and [accessQueue] are meant to have identical entries,
     * in theory they can get out of sync with enough concurrency due to thread preemption.
     * Overtime the inconsistency between the queues and the map might cause memory leaks, e.g.
     * when performing size-based evictions, an entry in the [cacheEntries] but not in [accessQueue]
     * would never be evicted.
     * Calling this function will remove entries which are in the [cacheEntries] map but not in either
     * [writeQueue] or [accessQueue].
     */
    private fun cleanUpDeadEntries() {
        val queues = listOfNotNull(writeQueue, accessQueue)
        queues.forEach { queue ->
            if (queue.size < cacheEntries.size) {
                // remove entries in the map but not in the queue
                val iterator = cacheEntries.iterator()
                for (item in iterator) {
                    val cacheEntry = item.value
                    if (!queue.contains(cacheEntry)) {
                        iterator.remove()
                        // also make sure the other queue does not have it
                        if (queue == writeQueue) {
                            accessQueue?.remove(cacheEntry)
                        } else {
                            writeQueue?.remove(cacheEntry)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}

/**
 * A cache entry holds the [key] and [value] pair,
 * along with the metadata needed to perform cache expiration and eviction.
 *
 * A cache entry can be reused by updating [value], [accessTimeNanos], or [writeTimeNanos],
 * as this allows us to avoid creating new instance of [CacheEntry] on every access and write.
 */
private class CacheEntry<Key : Any, Value : Any>(
    val key: Key,
    @Volatile var value: Value,
    @Volatile var weight: Int,
    @Volatile var accessTimeNanos: Long = Long.MAX_VALUE,
    @Volatile var writeTimeNanos: Long = Long.MAX_VALUE
)
