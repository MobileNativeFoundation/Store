package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.internal.definition.Timestamp

internal class RealBookkeeper<Key : Any>(
    private val realGetLastFailedSync: suspend (key: Key) -> Timestamp?,
    private val realSetLastFailedSync: suspend (key: Key, timestamp: Timestamp) -> Boolean,
    private val realClear: suspend (key: Key) -> Boolean,
    private val realClearAll: suspend () -> Boolean,
) : Bookkeeper<Key> {
    override suspend fun getLastFailedSync(key: Key): Long? = realGetLastFailedSync(key)

    override suspend fun setLastFailedSync(
        key: Key,
        timestamp: Long,
    ): Boolean = realSetLastFailedSync(key, timestamp)

    override suspend fun clear(key: Key): Boolean = realClear(key)

    override suspend fun clearAll(): Boolean = realClearAll()
}
