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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Main entry point for creating a [Store].
 */
@FlowPreview
@ExperimentalCoroutinesApi
interface StoreBuilder<Key : Any, Output : Any> {
    fun build(): Store<Key, Output>
    fun scope(scope: CoroutineScope): StoreBuilder<Key, Output>
    fun cachePolicy(memoryPolicy: MemoryPolicy?): StoreBuilder<Key, Output>
    fun disableCache(): StoreBuilder<Key, Output>

    /**
     * Connects a (non-[Flow]) source of truth that is accessible via [reader], [writer] and
     * [delete].
     *
     * @see persister
     */
    fun <NewOutput : Any> nonFlowingPersister(
        reader: suspend (Key) -> NewOutput?,
        writer: suspend (Key, Output) -> Unit,
        delete: (suspend (Key) -> Unit)? = null
    ): StoreBuilder<Key, NewOutput>

    /**
     * Connects a ([Flow]) source of truth that is accessed via [reader], [writer] and [delete].
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
     * @param delete deletes records in the source of truth
     *
     */
    fun <NewOutput : Any> persister(
        reader: (Key) -> Flow<NewOutput?>,
        writer: suspend (Key, Output) -> Unit,
        delete: (suspend (Key) -> Unit)? = null
    ): StoreBuilder<Key, NewOutput>

    companion object {
        /**
         * Creates a new [StoreBuilder] from a non-[Flow] fetcher.
         *
         * Use when creating a [Store] that fetches objects in an HTTP-like single response per
         * request protocol.
         *
         * @param fetcher a function for fetching network records.
         */
        fun <Key : Any, Output : Any> fromNonFlow(
            fetcher: suspend (key: Key) -> Output
        ): StoreBuilder<Key, Output> = BuilderImpl { key: Key ->
            flow {
                emit(fetcher(key))
            }
        }

        /**
         * Creates a new [StoreBuilder] from a [Flow] fetcher.
         *
         * Use when creating a [Store] that fetches objects in an websocket-like multiple responses
         * per request protocol.
         *
         * @param fetcher a function for fetching a flow of network records.
         */
        fun <Key : Any, Output : Any> from(
            fetcher: (key: Key) -> Flow<Output>
        ): StoreBuilder<Key, Output> = BuilderImpl(fetcher)
    }
}

@FlowPreview
@ExperimentalCoroutinesApi
private class BuilderImpl<Key : Any, Output : Any>(
    private val fetcher: (key: Key) -> Flow<Output>
) : StoreBuilder<Key, Output> {
    private var scope: CoroutineScope? = null
    private var cachePolicy: MemoryPolicy? = StoreDefaults.memoryPolicy

    private fun <NewOutput : Any> withSourceOfTruth(
        sourceOfTruth: SourceOfTruth<Key, Output, NewOutput>? = null
    ) = BuilderWithSourceOfTruth(fetcher, sourceOfTruth).let { builder ->
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

    override fun scope(scope: CoroutineScope): BuilderImpl<Key, Output> {
        this.scope = scope
        return this
    }

    override fun cachePolicy(memoryPolicy: MemoryPolicy?): BuilderImpl<Key, Output> {
        cachePolicy = memoryPolicy
        return this
    }

    override fun disableCache(): BuilderImpl<Key, Output> {
        cachePolicy = null
        return this
    }

    override fun <NewOutput : Any> nonFlowingPersister(
        reader: suspend (Key) -> NewOutput?,
        writer: suspend (Key, Output) -> Unit,
        delete: (suspend (Key) -> Unit)?
    ): BuilderWithSourceOfTruth<Key, Output, NewOutput> {
        return withSourceOfTruth(
            PersistentNonFlowingSourceOfTruth(
                realReader = reader,
                realWriter = writer,
                realDelete = delete
            )
        )
    }

    override fun <NewOutput : Any> persister(
        reader: (Key) -> Flow<NewOutput?>,
        writer: suspend (Key, Output) -> Unit,
        delete: (suspend (Key) -> Unit)?
    ): BuilderWithSourceOfTruth<Key, Output, NewOutput> {
        return withSourceOfTruth(
            PersistentSourceOfTruth(
                realReader = reader,
                realWriter = writer,
                realDelete = delete
            )
        )
    }

    override fun build(): Store<Key, Output> {
        return withSourceOfTruth<Output>().build()
    }
}

@FlowPreview
@ExperimentalCoroutinesApi
private class BuilderWithSourceOfTruth<Key : Any, Input : Any, Output : Any>(
    private val fetcher: (key: Key) -> Flow<Input>,
    private val sourceOfTruth: SourceOfTruth<Key, Input, Output>? = null
) : StoreBuilder<Key, Output> {
    private var scope: CoroutineScope? = null
    private var cachePolicy: MemoryPolicy? = StoreDefaults.memoryPolicy

    override fun scope(scope: CoroutineScope): BuilderWithSourceOfTruth<Key, Input, Output> {
        this.scope = scope
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

    override fun build(): Store<Key, Output> {
        @Suppress("UNCHECKED_CAST")
        return RealStore(
            scope = scope ?: GlobalScope,
            sourceOfTruth = sourceOfTruth,
            fetcher = fetcher,
            memoryPolicy = cachePolicy
        )
    }

    override fun <NewOutput : Any> persister(
        reader: (Key) -> Flow<NewOutput?>,
        writer: suspend (Key, Output) -> Unit,
        delete: (suspend (Key) -> Unit)?
    ): StoreBuilder<Key, NewOutput> = error("Multiple persisters are not supported")

    override fun <NewOutput : Any> nonFlowingPersister(
        reader: suspend (Key) -> NewOutput?,
        writer: suspend (Key, Output) -> Unit,
        delete: (suspend (Key) -> Unit)?
    ): StoreBuilder<Key, NewOutput> = error("Multiple persisters are not supported")
}
