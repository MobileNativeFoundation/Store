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
package com.dropbox.android.external.store4

import com.dropbox.android.external.store4.impl.PersistentNonFlowingSourceOfTruth
import com.dropbox.android.external.store4.impl.PersistentSourceOfTruth
import kotlinx.coroutines.flow.Flow

/**
 * Source of truth takes care of making any source (no matter if it has flowing reads or not) into
 * a common flowing API.
 *
 * A source of truth is usually backed by local storage. It's purpose is to eliminate the need
 * for waiting on network update before local modifications are available (via [Store.stream]).
 */
interface SourceOfTruth<Key, Input, Output> {
    val defaultOrigin: ResponseOrigin
    fun reader(key: Key): Flow<Output?>
    suspend fun write(key: Key, value: Input)
    suspend fun delete(key: Key)
    suspend fun deleteAll()

    companion object {
        /**
         * Creates a (non-[Flow]) source of truth that is accessible via [reader], [writer],
         * [delete], and [deleteAll].
         *
         * @see persister
         */
        fun <Key : Any, Input : Any, Output : Any> fromNonFlow(
            reader: suspend (Key) -> Output?,
            writer: suspend (Key, Input) -> Unit,
            delete: (suspend (Key) -> Unit)? = null,
            deleteAll: (suspend () -> Unit)? = null
        ): SourceOfTruth<Key, Input, Output> = PersistentNonFlowingSourceOfTruth(
            realReader = reader,
            realWriter = writer,
            realDelete = delete,
            realDeleteAll = deleteAll
        )

        /**
         * Creates a ([kotlinx.coroutines.flow.Flow]) source of truth that is accessed via [reader], [writer] and [delete].
         *
         * A source of truth is usually backed by local storage. It's purpose is to eliminate the need
         * for waiting on network update before local modifications are available (via [Store.stream]).
         *
         * @param [com.dropbox.android.external.store4.Persister] reads records from the source of truth
         * WARNING: Delete operation is not supported when using a legacy [com.dropbox.android.external.store4.Persister],
         * please use another override
         */
        fun <Key, Output> fromLegacyPresister(
            persister: Persister<Output, Key>
        ): SourceOfTruth<Key, Output, Output> = PersistentNonFlowingSourceOfTruth(
            realReader = { key -> persister.read(key) },
            realWriter = { key, input -> persister.write(key, input) },
            realDelete = { error("Delete is not implemented in legacy persisters") },
            realDeleteAll = { error("Delete all is not implemented in legacy persisters") }
        )

        /**
         * Creates a ([kotlinx.coroutines.flow.Flow]) source of truth that is accessed via [reader], [writer] and [delete].
         *
         * For maximal flexibility, [writer]'s record type ([Output]] and [reader]'s record type
         * ([NewOutput]) are not identical. This allows us to read one type of objects from network and
         * transform them to another type when placing them in local storage.
         *
         * A source of truth is usually backed by local storage. It's purpose is to eliminate the need
         * for waiting on network update before local modifications are available (via [Store.stream]).
         *
         * @param reader reads records from the source of truth
         * @param writer writes records **coming in from the fetcher (network)** to the source of truth.
         * Writing local user updates to the source of truth via [Store] is currently not supported.
         * @param delete deletes records in the source of truth for the give key
         * @param deleteAll deletes all records in the source of truth
         *
         */
        fun <Key : Any, Input : Any, Output : Any> from(
            reader: (Key) -> Flow<Output?>,
            writer: suspend (Key, Input) -> Unit,
            delete: (suspend (Key) -> Unit)? = null,
            deleteAll: (suspend () -> Unit)? = null
        ): SourceOfTruth<Key, Input, Output> = PersistentSourceOfTruth(
            realReader = reader,
            realWriter = writer,
            realDelete = delete,
            realDeleteAll = deleteAll
        )
    }
}

