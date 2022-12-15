@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreClearer
import org.mobilenativefoundation.store.store5.StoreDeleter
import org.mobilenativefoundation.store.store5.StoreReader
import org.mobilenativefoundation.store.store5.StoreWriter

internal class RealStore<Key : Any, StoreRepresentation : Any, CommonRepresentation : Any>(
    private val realReader: StoreReader<Key, StoreRepresentation>,
    private val realWriter: StoreWriter<Key, StoreRepresentation>,
    private val realDeleter: StoreDeleter<Key>? = null,
    private val realClearer: StoreClearer? = null,
    override val converter: Store.Converter<StoreRepresentation, CommonRepresentation>?,
) : Store<Key, StoreRepresentation, CommonRepresentation> {
    override fun read(key: Key): Flow<CommonRepresentation?> = realReader(key).map {
        if (it != null) {
            converter?.toCommonRepresentation(it) ?: it as? CommonRepresentation
        } else {
            null
        }
    }

    override suspend fun write(key: Key, input: CommonRepresentation): Boolean {
        val storeRepresentation = converter?.toStoreRepresentation(input) ?: input as? StoreRepresentation
        return if (storeRepresentation != null) {
            realWriter(key, storeRepresentation)
        } else {
            false
        }
    }

    override suspend fun delete(key: Key): Boolean = realDeleter?.invoke(key) ?: false
    override suspend fun clear(): Boolean = realClearer?.invoke() ?: false
}
