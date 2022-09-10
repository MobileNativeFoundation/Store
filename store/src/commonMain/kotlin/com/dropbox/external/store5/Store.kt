package com.dropbox.external.store5

import com.dropbox.external.store5.impl.RealStore
import kotlinx.coroutines.flow.Flow

typealias Read<Key, Output> = suspend (key: Key) -> Flow<Output?>
typealias Write<Key, Input> = suspend (key: Key, input: Input) -> Boolean
typealias Delete<Key> = suspend (key: Key) -> Boolean
typealias DeleteAll = suspend () -> Boolean

/**
 * Interacts with a data source.
 * We recommend [Store] delegate to one [Persister]. However, [Store] can delegate to any source(s) of data.
 * A [Market] implementation requires at least one [Store]. But typical applications have at least two: one bound to a memory cache and another bound to a database.
 * @see [Persister].
 * @see [Market].
 */

interface Store<Key : Any, Input : Any, Output : Any> {
    suspend fun read(key: Key): Flow<Output?>
    suspend fun write(key: Key, input: Input): Boolean
    suspend fun delete(key: Key): Boolean
    suspend fun deleteAll(): Boolean

    companion object {
        fun <Key : Any, Input : Any, Output : Any> by(
            read: Read<Key, Output>,
            write: Write<Key, Input>,
            delete: Delete<Key>,
            deleteAll: DeleteAll
        ): Store<Key, Input, Output> = RealStore(
            read, write, delete, deleteAll
        )
    }
}