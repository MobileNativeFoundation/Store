package com.dropbox.android.external.cache4

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
     * When called concurrently, all actions associated with the same [key] are mutually exclusive.
     */
    fun <T> synchronizedFor(key: Key, action: () -> T): T {
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
            val lockEntry = keyBasedLocks[key] ?: LockEntry(ReentrantLock(), 0)
            // increment the counter to indicate a new thread is using the lock
            lockEntry.counter++
            // save the lock entry to the map if it has just been created
            if (keyBasedLocks[key] == null) {
                keyBasedLocks[key] = lockEntry
            }
            return lockEntry.lock
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
            lockEntry.counter--
            if (lockEntry.counter == 0) {
                keyBasedLocks.remove(key)
            }
        }
    }
}

private class LockEntry(
    val lock: Lock,
    var counter: Int
)
