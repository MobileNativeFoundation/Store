package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.store5.impl.RealStore
import kotlinx.coroutines.flow.Flow

typealias StoreReader<Key, Output> = (key: Key) -> Flow<Output?>
typealias StoreWriter<Key, Input> = suspend (key: Key, input: Input) -> Boolean
typealias StoreDeleter<Key> = suspend (key: Key) -> Boolean
typealias StoreClearer = suspend () -> Boolean

/**
 * Interacts with a data source.
 * We recommend [Store] delegate to one [Persister]. However, [Store] can delegate to any source(s) of data.
 * A [Market] implementation requires at least one [Store]. But typical applications have at least two: one bound to a memory cache and another bound to a database.
 * @see [Persister].
 * @see [Market].
 */

interface Store<Key : Any, Input : Any, Output : Any> {
    /**
     * Reads data from [Store] using [Key].
     */
    fun read(key: Key): Flow<Output?>

    /**
     * Writes data to [Store] using [Key] and [Input].
     */
    suspend fun write(key: Key, input: Input): Boolean

    /**
     * Deletes data from [Store] associated to [Key].
     */
    suspend fun delete(key: Key): Boolean

    /**
     * Deletes all data from [Store].
     */
    suspend fun clear(): Boolean

    companion object {
        fun <Key : Any, Input : Any, Output : Any> by(
            reader: StoreReader<Key, Output>,
            writer: StoreWriter<Key, Input>,
            deleter: StoreDeleter<Key>,
            clearer: StoreClearer
        ): Store<Key, Input, Output> = RealStore(
            reader, writer, deleter, clearer
        )
    }
}
