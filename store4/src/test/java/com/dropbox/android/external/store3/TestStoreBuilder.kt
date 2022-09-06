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

import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.MemoryPolicy
import com.dropbox.android.external.store4.SourceOfTruth
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.impl.PersistentSourceOfTruth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flow
import kotlin.time.ExperimentalTime

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
            cached: Boolean = false,
            cacheMemoryPolicy: MemoryPolicy<Any, Any>? = null,
            reader: ((Key) -> Output?)? = null,
            writer: ((Key, Output) -> Boolean)? = null,
            fetcher: Fetcher<Key, Output>
        ): TestStoreBuilder<Key, Output> {
            return TestStoreBuilder(
                buildStore = {
                    StoreBuilder.let {
                        if (reader == null || writer == null) {
                            it.from<Key, Output>(fetcher)
                        } else {
                            it.from(fetcher, sourceOfTruth(reader, writer))
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
                        .build()
                }
            )
        }

        internal fun <Key, Output> sourceOfTruth(
            reader: (Key) -> Output?,
            writer: (Key, Output) -> Boolean
        ): SourceOfTruth<Key, Output, Output> {
            return PersistentSourceOfTruth(
                realReader = { key ->
                    flow {
                        emit(reader(key))
                    }
                },
                realWriter = { key, value ->
                    writer(key, value)
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
