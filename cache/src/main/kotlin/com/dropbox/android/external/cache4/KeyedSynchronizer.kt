package com.dropbox.android.external.cache4

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Provides a mechanism for performing key-based synchronization.
 */
internal class KeyedSynchronizer<Key : Any> {

    private val keyBasedLocks: MutableMap<Key, LockEntry> = hashMapOf()

    private val mapLock = Any()

    /**
     * Executes the given [action] under a lock associated with the [key].
     * When called concurrently, all actions are executed sequentially for the same [key].
     */
    fun <T> run(key: Key, action: () -> T): T {
        return getLock(key).withLock {
            try {
                action()
            } finally {
                removeLock(key)
            }
        }
    }

    /**
     * Try to get a [LockEntry] for the given [key] from the map.
     * If one cannot be found, create a new [LockEntry], save it to the map, and return it.
     */
    private fun getLock(key: Key): Lock {
        synchronized(mapLock) {
            val lockEntry = keyBasedLocks[key]
            if (lockEntry != null) {
                // increment the counter to indicate a new thread is using the lock
                lockEntry.counter.incrementAndGet()
                return lockEntry.lock
            }
            // create a new lock entry with counter set to 1 indicating the lock is used by 1 thread
            keyBasedLocks[key] = LockEntry(ReentrantLock(), AtomicInteger(1))
            return keyBasedLocks[key]!!.lock
        }
    }

    /**
     * Remove the [LockEntry] associated with the given [key] from the map
     * if no other thread is using the lock.
     */
    private fun removeLock(key: Key) {
        synchronized(mapLock) {
            // decrement the counter to indicate the lock is no longer needed for this thread,
            // then remove the lock entry from map if no other thread is still holding this lock
            val lockEntry = keyBasedLocks[key] ?: return
            if (lockEntry.counter.decrementAndGet() == 0) {
                keyBasedLocks.remove(key)
            }
        }
    }
}

private class LockEntry(
    val lock: Lock,
    val counter: AtomicInteger
)
