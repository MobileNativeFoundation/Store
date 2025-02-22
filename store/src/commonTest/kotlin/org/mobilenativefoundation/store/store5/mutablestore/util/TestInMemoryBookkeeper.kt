package org.mobilenativefoundation.store.store5.mutablestore.util

import org.mobilenativefoundation.store.store5.Bookkeeper

class TestInMemoryBookkeeper<Key : Any> : Bookkeeper<Key> {
    private val failedSyncMap = mutableMapOf<Key, Long>()

    override suspend fun getLastFailedSync(key: Key): Long? {
        return failedSyncMap[key]
    }

    override suspend fun setLastFailedSync(
        key: Key,
        timestamp: Long,
    ): Boolean {
        failedSyncMap[key] = timestamp
        return true
    }

    override suspend fun clear(key: Key): Boolean {
        return failedSyncMap.remove(key) != null
    }

    override suspend fun clearAll(): Boolean {
        failedSyncMap.clear()
        return true
    }
}
