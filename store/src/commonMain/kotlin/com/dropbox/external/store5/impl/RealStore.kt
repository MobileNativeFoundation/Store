package com.dropbox.external.store5.impl

import com.dropbox.external.store5.Delete
import com.dropbox.external.store5.DeleteAll
import com.dropbox.external.store5.Read
import com.dropbox.external.store5.Store
import com.dropbox.external.store5.Write
import kotlinx.coroutines.flow.Flow

internal class RealStore<Key : Any, Input : Any, Output : Any>(
    private val _read: Read<Key, Output>,
    private val _write: Write<Key, Input>,
    private val _delete: Delete<Key>,
    private val _deleteAll: DeleteAll
) : Store<Key, Input, Output> {
    override suspend fun read(key: Key): Flow<Output?> = _read(key)
    override suspend fun write(key: Key, input: Input): Boolean = _write(key, input)
    override suspend fun delete(key: Key): Boolean = _delete(key)
    override suspend fun deleteAll(): Boolean = _deleteAll()
}