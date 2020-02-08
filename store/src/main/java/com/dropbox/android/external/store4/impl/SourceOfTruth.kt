/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 interface SourceOfTruth<Key, Input, Output> {
    val defaultOrigin: ResponseOrigin
    fun reader(key: Key): Flow<Output?>
    suspend fun write(key: Key, value: Input)
    suspend fun delete(key: Key)
    suspend fun deleteAll()
    // for testing
    suspend fun getSize(): Int
}

internal class PersistentSourceOfTruth<Key, Input, Output>(
    private val realReader: (Key) -> Flow<Output?>,
    private val realWriter: suspend (Key, Input) -> Unit,
    private val realDelete: (suspend (Key) -> Unit)? = null,
    private val realDeleteAll: (suspend () -> Unit)? = null
) : SourceOfTruth<Key, Input, Output> {
    override val defaultOrigin = ResponseOrigin.Persister

    override fun reader(key: Key): Flow<Output?> = realReader(key)

    override suspend fun write(key: Key, value: Input) = realWriter(key, value)

    override suspend fun delete(key: Key) {
        realDelete?.invoke(key)
    }

    override suspend fun deleteAll() {
        realDeleteAll?.invoke()
    }

    // for testing
    override suspend fun getSize(): Int {
        throw UnsupportedOperationException("not supported for persistent")
    }
}

internal class PersistentNonFlowingSourceOfTruth<Key, Input, Output>(
    private val realReader: suspend (Key) -> Output?,
    private val realWriter: suspend (Key, Input) -> Unit,
    private val realDelete: (suspend (Key) -> Unit)? = null,
    private val realDeleteAll: (suspend () -> Unit)?
) : SourceOfTruth<Key, Input, Output> {
    override val defaultOrigin = ResponseOrigin.Persister

    override fun reader(key: Key): Flow<Output?> = flow {
        emit(realReader(key))
    }

    override suspend fun write(key: Key, value: Input) = realWriter(key, value)

    override suspend fun delete(key: Key) {
        realDelete?.invoke(key)
    }

    override suspend fun deleteAll() {
        realDeleteAll?.invoke()
    }

    // for testing
    override suspend fun getSize(): Int {
        throw UnsupportedOperationException("not supported for persistent")
    }
}
