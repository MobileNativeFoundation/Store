package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.ResponseOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Source of truth takes care of making any source (no matter if it has flowing reads or not) into
 * a common flowing API. Used w/ a [SourceOfTruthWithBarrier] in front of it in the
 * [RealStore] implementation to avoid dispatching values to downstream while
 * a write is in progress.
 */
 internal interface SourceOfTruth<Key, Input, Output> {
    val defaultOrigin: ResponseOrigin
    fun reader(key: Key): Flow<Output?>
    suspend fun write(key: Key, value: Input)
    suspend fun delete(key: Key)
    // for testing
    suspend fun getSize(): Int
}

internal class PersistentSourceOfTruth<Key, Input, Output>(
    private val realReader: (Key) -> Flow<Output?>,
    private val realWriter: suspend (Key, Input) -> Unit,
    private val realDelete: (suspend (Key) -> Unit)? = null
) : SourceOfTruth<Key, Input, Output> {
    override val defaultOrigin = ResponseOrigin.Persister
    override fun reader(key: Key): Flow<Output?> = realReader(key)
    override suspend fun write(key: Key, value: Input) = realWriter(key, value)
    override suspend fun delete(key: Key) {
        realDelete?.invoke(key)
    }

    // for testing
    override suspend fun getSize(): Int {
        throw UnsupportedOperationException("not supported for persistent")
    }
}

internal class PersistentNonFlowingSourceOfTruth<Key, Input, Output>(
    private val realReader: suspend (Key) -> Output?,
    private val realWriter: suspend (Key, Input) -> Unit,
    private val realDelete: (suspend (Key) -> Unit)? = null
) : SourceOfTruth<Key, Input, Output> {
    override val defaultOrigin = ResponseOrigin.Persister
    override fun reader(key: Key): Flow<Output?> = flow {
        emit(realReader(key))
    }
    override suspend fun write(key: Key, value: Input) = realWriter(key, value)
    override suspend fun delete(key: Key) {
        realDelete?.invoke(key)
    }

    // for testing
    override suspend fun getSize(): Int {
        throw UnsupportedOperationException("not supported for persistent")
    }
}
