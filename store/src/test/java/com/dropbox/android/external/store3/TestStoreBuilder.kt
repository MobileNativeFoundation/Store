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
package com.dropbox.android.external.store3

import com.dropbox.android.external.store3.util.KeyParser
import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.MemoryPolicy
import com.dropbox.android.external.store4.Persister
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.impl.PersistentSourceOfTruth
import com.dropbox.android.external.store4.impl.SourceOfTruth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.flow
import kotlin.time.ExperimentalTime

@FlowPreview
@ExperimentalCoroutinesApi
data class TestStoreBuilder<Key : Any, Output : Any>(
    private val buildStore: () -> Store<Key, Output>
) {
    fun build(storeType: TestStoreType): Store<Key, out Output> = when (storeType) {
        TestStoreType.FlowStore -> buildStore()
    }

    @OptIn(ExperimentalTime::class)
    companion object {

        fun <Key : Any, Output : Any> from(
            scope: CoroutineScope,
            fetcher: Fetcher<Output, Key>,
            persister: Persister<Output, Key>? = null,
            inflight: Boolean = true
        ): TestStoreBuilder<Key, Output> = from(
            scope = scope,
            inflight = inflight,
            persister = persister,
            fetcher = { fetcher.invoke(it) }
        )

        @Suppress("UNCHECKED_CAST")
        fun <Key : Any, Output : Any> from(
            scope: CoroutineScope,
            inflight: Boolean = true,
            cached: Boolean = false,
            cacheMemoryPolicy: MemoryPolicy? = null,
            persister: Persister<Output, Key>? = null,
            fetcher: suspend (Key) -> Output
        ): TestStoreBuilder<Key, Output> = from(
            scope = scope,
            inflight = inflight,
            cached = cached,
            cacheMemoryPolicy = cacheMemoryPolicy,
            persister = persister,
            fetcher = object : Fetcher<Output, Key> {
                override suspend fun invoke(key: Key): Output = fetcher(key)
            }
        )

        @Suppress("UNCHECKED_CAST")
        fun <Key : Any, Output : Any> from(
            scope: CoroutineScope,
            inflight: Boolean = true,
            cached: Boolean = false,
            cacheMemoryPolicy: MemoryPolicy? = null,
            persister: Persister<Output, Key>? = null,
            // parser that runs after fetch
            fetchParser: KeyParser<Key, Output, Output>? = null,
            // parser that runs after get from db
            postParser: KeyParser<Key, Output, Output>? = null,
            fetcher: Fetcher<Output, Key>
        ): TestStoreBuilder<Key, Output> {
            return TestStoreBuilder(
                buildStore = {
                    StoreBuilder
                        .from { key: Key ->
                            flow {
                                val value = fetcher.invoke(key = key)
                                if (fetchParser != null) {
                                    emit(fetchParser.apply(key, value))
                                } else {
                                    emit(value)
                                }
                            }
                        }
                        .scope(scope)
                        .let {
                            if (cached) {
                                cacheMemoryPolicy?.let { cacheMemoryPolicy ->
                                    it.cachePolicy(
                                        cacheMemoryPolicy
                                    )
                                } ?: it
                            } else {
                                it.disableCache()
                            }
                        }
                        .let {
                            if (persister == null) {
                                it
                            } else {
                                val sourceOfTruth = sourceOfTruthFromLegacy(persister, postParser)
                                it.persister(
                                    sourceOfTruth::reader,
                                    sourceOfTruth::write,
                                    sourceOfTruth::delete
                                )
                            }
                        }.build()
                }
            )
        }

        internal fun <Key, Output> sourceOfTruthFromLegacy(
            persister: Persister<Output, Key>,
            // parser that runs after get from db
            postParser: KeyParser<Key, Output, Output>? = null
        ): SourceOfTruth<Key, Output, Output> {
            return PersistentSourceOfTruth(
                realReader = { key ->
                    flow {
                        if (postParser == null) {
                            emit(persister.read(key))
                        } else {
                            persister.read(key)?.let {
                                val postParsed = postParser.apply(key, it)
                                emit(postParsed)
                            } ?: emit(null)
                        }
                    }
                },
                realWriter = { key, value ->
                    persister.write(key, value)
                }
            )
        }
    }

    // wraps a regular fun to suspend, couldn't figure out how to create suspend fun variables :/
    private class SuspendWrapper<P0, R>(
        val f: (P0) -> R
    ) {
        suspend fun apply(input: P0): R = f(input)
    }
}

enum class TestStoreType {
    FlowStore
}
