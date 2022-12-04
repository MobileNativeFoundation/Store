package com.dropbox.external.store5.impl

import com.dropbox.external.store5.Store
import com.dropbox.external.store5.StoreClearer
import com.dropbox.external.store5.StoreDeleter
import com.dropbox.external.store5.StoreReader
import com.dropbox.external.store5.StoreWriter
import kotlinx.coroutines.flow.Flow

internal class RealStore<Key : Any, Input : Any, Output : Any>(
    private val realReader: StoreReader<Key, Output>,
    private val realWriter: StoreWriter<Key, Input>,
    private val realDeleter: StoreDeleter<Key>? = null,
    private val realClearer: StoreClearer? = null
) : Store<Key, Input, Output> {
    override fun read(key: Key): Flow<Output?> = realReader(key)
    override suspend fun write(key: Key, input: Input): Boolean = realWriter(key, input)
    override suspend fun delete(key: Key): Boolean = realDeleter?.invoke(key) ?: false
    override suspend fun clear(): Boolean = realClearer?.invoke() ?: false
}
