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
import com.dropbox.android.external.store4.impl.RealStore
import com.dropbox.android.external.store4.impl.SourceOfTruth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface StoreBuilder<Key, Output> {
    fun build(): Store<Key, Output>
    fun scope(scope: CoroutineScope): StoreBuilder<Key, Output>
    fun cachePolicy(memoryPolicy: MemoryPolicy?): StoreBuilder<Key, Output>
    fun disableCache(): StoreBuilder<Key, Output>

    companion object {
        fun <Key, Output> fromNonFlow(
                fetcher: suspend (key: Key) -> Output
        ) = Builder { key: Key ->
            flow {
                emit(fetcher(key))
            }
        }

        fun <Key, Output> from(
                fetcher: (key: Key) -> Flow<Output>
        ) = Builder(fetcher)
    }
}

class Builder<Key, Output>(
        private val fetcher: (key: Key) -> Flow<Output>
) : StoreBuilder<Key, Output> {
    private var scope: CoroutineScope? = null
    private var cachePolicy: MemoryPolicy? = StoreDefaults.memoryPolicy

    private fun <NewOutput> withSourceOfTruth() = BuilderWithSourceOfTruth<Key, Output, NewOutput>(
            fetcher
    ).let { builder ->
        if (cachePolicy == null) {
            builder.disableCache()
        } else {
            builder.cachePolicy(cachePolicy)
        }
    }.let { builder ->
        scope?.let {
            builder.scope(it)
        } ?: builder
    }

    override fun scope(scope: CoroutineScope): Builder<Key, Output> {
        this.scope = scope
        return this
    }

    override fun cachePolicy(memoryPolicy: MemoryPolicy?): Builder<Key, Output> {
        cachePolicy = memoryPolicy
        return this
    }

    override fun disableCache(): Builder<Key, Output> {
        cachePolicy = null
        return this
    }

    fun <NewOutput> nonFlowingPersister(
            reader: suspend (Key) -> NewOutput?,
            writer: suspend (Key, Output) -> Unit,
            delete: (suspend (Key) -> Unit)? = null
    ): BuilderWithSourceOfTruth<Key, Output, NewOutput> {
        return withSourceOfTruth<NewOutput>().nonFlowingPersister(
                reader = reader,
                writer = writer,
                delete = delete
        )
    }

    fun <NewOutput> persister(
            reader: (Key) -> Flow<NewOutput?>,
            writer: suspend (Key, Output) -> Unit,
            delete: (suspend (Key) -> Unit)? = null
    ): BuilderWithSourceOfTruth<Key, Output, NewOutput> {
        return withSourceOfTruth<NewOutput>().persister(
                reader = reader,
                writer = writer,
                delete = delete
        )
    }

    internal fun <NewOutput> sourceOfTruth(
            sourceOfTruth: SourceOfTruth<Key, Output, NewOutput>
    ): BuilderWithSourceOfTruth<Key, Output, NewOutput> {
        return withSourceOfTruth<NewOutput>().sourceOfTruth(sourceOfTruth)
    }

    override fun build(): Store<Key, Output> {
        return withSourceOfTruth<Output>().build()
    }
}

class BuilderWithSourceOfTruth<Key, Input, Output>(
        private val fetcher: (key: Key) -> Flow<Input>
) : StoreBuilder<Key, Output> {
    private var scope: CoroutineScope? = null
    private var sourceOfTruth: SourceOfTruth<Key, Input, Output>? = null
    private var cachePolicy: MemoryPolicy? = StoreDefaults.memoryPolicy

    override fun scope(scope: CoroutineScope): BuilderWithSourceOfTruth<Key, Input, Output> {
        this.scope = scope
        return this
    }

    fun nonFlowingPersister(
            reader: suspend (Key) -> Output?,
            writer: suspend (Key, Input) -> Unit,
            delete: (suspend (Key) -> Unit)? = null
    ): BuilderWithSourceOfTruth<Key, Input, Output> {
        sourceOfTruth = PersistentNonFlowingSourceOfTruth(
                realReader = reader,
                realWriter = writer,
                realDelete = delete
        )
        return this
    }

    fun persister(
            reader: (Key) -> Flow<Output?>,
            writer: suspend (Key, Input) -> Unit,
            delete: (suspend (Key) -> Unit)? = null
    ): BuilderWithSourceOfTruth<Key, Input, Output> {
        sourceOfTruth = PersistentSourceOfTruth(
                realReader = reader,
                realWriter = writer,
                realDelete = delete
        )
        return this
    }

    internal fun sourceOfTruth(
            sourceOfTruth: SourceOfTruth<Key, Input, Output>
    ): BuilderWithSourceOfTruth<Key, Input, Output> {
        this.sourceOfTruth = sourceOfTruth
        return this
    }

    override fun cachePolicy(memoryPolicy: MemoryPolicy?): BuilderWithSourceOfTruth<Key, Input, Output> {
        cachePolicy = memoryPolicy
        return this
    }

    override fun disableCache(): BuilderWithSourceOfTruth<Key, Input, Output> {
        cachePolicy = null
        return this
    }

    @ExperimentalCoroutinesApi
    override fun build(): Store<Key, Output> {
        @Suppress("UNCHECKED_CAST")
        return RealStore(
                scope = scope ?: GlobalScope,
                sourceOfTruth = sourceOfTruth,
                fetcher = fetcher,
                memoryPolicy = cachePolicy
        )
    }
}