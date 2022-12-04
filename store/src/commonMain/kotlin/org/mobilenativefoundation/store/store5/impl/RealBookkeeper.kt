package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.Bookkeeper

internal class RealBookkeeper<Key : Any>(
    private val _read: suspend (key: Key) -> Long?,
    private val _write: suspend (key: Key, timestamp: Long) -> Boolean,
    private val _delete: suspend (key: Key) -> Boolean,
    private val _deleteAll: suspend () -> Boolean
) : Bookkeeper<Key> {
    override suspend fun getTimestampLastFailedSync(key: Key): Long? = _read(key)

    override suspend fun setTimestampLastFailedSync(key: Key, timestamp: Long): Boolean = _write(key, timestamp)

    override suspend fun delete(key: Key): Boolean = _delete(key)

    override suspend fun deleteAll(): Boolean = _deleteAll()
}
