package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store.store5.impl.RealStore

typealias StoreReader<Key, StoreRepresentation> = (key: Key) -> Flow<StoreRepresentation?>
typealias StoreWriter<Key, StoreRepresentation> = suspend (key: Key, input: StoreRepresentation) -> Boolean
typealias StoreDeleter<Key> = suspend (key: Key) -> Boolean
typealias StoreClearer = suspend () -> Boolean

/**
 * Interacts with a data source.
 * A [Market] implementation requires at least one [Store]. But typical applications have at least two: one bound to a memory cache and another bound to a database.
 * @see [Market].
 */

interface Store<Key : Any, StoreRepresentation : Any, CommonRepresentation : Any> {
    /**
     * Reads data from [Store] using [Key].
     */
    fun read(key: Key): Flow<CommonRepresentation?>

    /**
     * Writes data to [Store] using [Key] and [CommonRepresentation].
     */
    suspend fun write(key: Key, input: CommonRepresentation): Boolean

    /**
     * Deletes data from [Store] associated to [Key].
     */
    suspend fun delete(key: Key): Boolean

    /**
     * Deletes all data from [Store].
     */
    suspend fun clear(): Boolean

    val converter: Converter<StoreRepresentation, CommonRepresentation>?

    interface Converter<StoreRepresentation : Any, CommonRepresentation : Any> {
        fun toCommonRepresentation(storeRepresentation: StoreRepresentation): CommonRepresentation
        fun toStoreRepresentation(commonRepresentation: CommonRepresentation): StoreRepresentation
    }

    companion object {
        fun <Key : Any, StoreRepresentation : Any, CommonRepresentation : Any> by(
            reader: StoreReader<Key, StoreRepresentation>,
            writer: StoreWriter<Key, StoreRepresentation>,
            deleter: StoreDeleter<Key>,
            clearer: StoreClearer,
            converter: Converter<StoreRepresentation, CommonRepresentation>? = null
        ): Store<Key, StoreRepresentation, CommonRepresentation> = RealStore(
            reader, writer, deleter, clearer, converter
        )
    }
}
