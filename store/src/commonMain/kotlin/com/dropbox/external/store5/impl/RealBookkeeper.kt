package com.dropbox.external.store5.impl

import com.dropbox.external.store5.Bookkeeper


internal class RealBookkeeper<Key : Any>(
    private val _read: suspend (key: Key) -> Long?,
    private val _write: suspend (key: Key, timestamp: Long) -> Boolean,
    private val _delete: suspend (key: Key) -> Boolean,
    private val _deleteAll: suspend () -> Boolean
) : Bookkeeper<Key> {
    override suspend fun read(key: Key): Long? = _read(key)

    override suspend fun write(key: Key, timestamp: Long): Boolean = _write(key, timestamp)

    override suspend fun delete(key: Key): Boolean = _delete(key)

    override suspend fun deleteAll(): Boolean = _deleteAll()
}