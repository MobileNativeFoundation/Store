package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.SourceOfTruth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class PersistentSourceOfTruth<Key, Input, Output>(
    private val realReader: (Key) -> Flow<Output?>,
    private val realWriter: suspend (Key, Input) -> Unit,
    private val realDelete: (suspend (Key) -> Unit)? = null,
    private val realDeleteAll: (suspend () -> Unit)? = null
) : SourceOfTruth<Key, Input, Output> {
    override val defaultOrigin =
        ResponseOrigin.Persister

    override fun reader(key: Key): Flow<Output?> = realReader(key)

    override suspend fun write(key: Key, value: Input) = realWriter(key, value)

    override suspend fun delete(key: Key) {
        realDelete?.invoke(key)
    }

    override suspend fun deleteAll() {
        realDeleteAll?.invoke()
    }
}

internal class PersistentNonFlowingSourceOfTruth<Key, Input, Output>(
    private val realReader: suspend (Key) -> Output?,
    private val realWriter: suspend (Key, Input) -> Unit,
    private val realDelete: (suspend (Key) -> Unit)? = null,
    private val realDeleteAll: (suspend () -> Unit)?
) : SourceOfTruth<Key, Input, Output> {
    override val defaultOrigin =
        ResponseOrigin.Persister

    override fun reader(key: Key): Flow<Output?> =
        flow {
            emit(realReader(key))
        }

    override suspend fun write(key: Key, value: Input) = realWriter(key, value)

    override suspend fun delete(key: Key) {
        realDelete?.invoke(key)
    }

    override suspend fun deleteAll() {
        realDeleteAll?.invoke()
    }
}
