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
package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.mobilenativefoundation.store.store5.SourceOfTruth

internal class PersistentSourceOfTruth<Key, Input, Output>(
    private val realReader: (Key) -> Flow<Output?>,
    private val realWriter: suspend (Key, Input) -> Unit,
    private val realDelete: (suspend (Key) -> Unit)? = null,
    private val realDeleteAll: (suspend () -> Unit)? = null
) : SourceOfTruth<Key, Input, Output> {

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
