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
    /**
     * A store multicasts same [Output] value to many consumers (Similar to RxJava.share()), by default
     *  [Store] will open a global scope for management of shared responses, if instead you'd like to control
     *  the scope that sharing/multicasting happens in you can pass a @param [scope]
     *
     *   @param scope - scope to use for sharing
     */
    fun scope(scope: CoroutineScope): StoreBuilder<Key, Output>

    /**
     * controls eviction policy for a store cache, use [MemoryPolicy.MemoryPolicyBuilder] to configure a TTL
     *  or size based eviction
     *  Example: MemoryPolicy.builder().setExpireAfterWrite(10).setExpireAfterTimeUnit(TimeUnit.SECONDS).build()
     */
    fun cachePolicy(memoryPolicy: MemoryPolicy?): StoreBuilder<Key, Output>

    /**
     * by default a Store caches in memory with a default policy of max items = 16
     */
    fun disableCache(): StoreBuilder<Key, Output>

    fun<UnwrappedOutput: Any> fetcherTransformer(fetcherTransformer: (Output) -> FetcherResult<UnwrappedOutput>)

    /**
     * Connects a (non-[Flow]) source of truth that is accessible via [reader], [writer],
     * [delete], and [deleteAll].
     *
     * @see persister
     */
    fun <NewOutput : Any> nonFlowingPersister(
        reader: suspend (Key) -> NewOutput?,
        writer: suspend (Key, Output) -> Unit,
        delete: (suspend (Key) -> Unit)? = null,
        deleteAll: (suspend () -> Unit)? = null
    ): StoreBuilder<Key, NewOutput>

    /**
     * Connects a ([kotlinx.coroutines.flow.Flow]) source of truth that is accessed via [reader], [writer] and [delete].
     *
     * A source of truth is usually backed by local storage. It's purpose is to eliminate the need
     * for waiting on network update before local modifications are available (via [Store.stream]).
     *
     * @param [com.dropbox.android.external.store4.Persister] reads records from the source of truth
     * WARNING: Delete operation is not supported when using a legacy [com.dropbox.android.external.store4.Persister],
     * please use another override
     */
    fun nonFlowingPersisterLegacy(
        persister: Persister<Output, Key>
    ): StoreBuilder<Key, Output>

    /**
     * Connects a ([kotlinx.coroutines.flow.Flow]) source of truth that is accessed via [reader], [writer] and [delete].
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
    fun <NewOutput : Any> persister(
        reader: (Key) -> Flow<NewOutput?>,
        writer: suspend (Key, Output) -> Unit,
        delete: (suspend (Key) -> Unit)? = null,
        deleteAll: (suspend () -> Unit)? = null
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

    private fun withLegacySourceOfTruth(
        sourceOfTruth: PersistentNonFlowingSourceOfTruth<Key, Output, Output>
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
        delete: (suspend (Key) -> Unit)?,
        deleteAll: (suspend () -> Unit)?
    ): BuilderWithSourceOfTruth<Key, Output, NewOutput> {
        return withSourceOfTruth(
            PersistentNonFlowingSourceOfTruth(
                realReader = reader,
                realWriter = writer,
                realDelete = delete,
                realDeleteAll = deleteAll
            )
        )
    }

    override fun nonFlowingPersisterLegacy(
        persister: Persister<Output, Key>
    ): BuilderWithSourceOfTruth<Key, Output, Output> {
        val sourceOfTruth: PersistentNonFlowingSourceOfTruth<Key, Output, Output> =
            PersistentNonFlowingSourceOfTruth(
                realReader = { key -> persister.read(key) },
                realWriter = { key, input -> persister.write(key, input) },
                realDelete = { error("Delete is not implemented in legacy persisters") },
                realDeleteAll = { error("Delete all is not implemented in legacy persisters") }
            )
        return withLegacySourceOfTruth(sourceOfTruth)
    }

    override fun <NewOutput : Any> persister(
        reader: (Key) -> Flow<NewOutput?>,
        writer: suspend (Key, Output) -> Unit,
        delete: (suspend (Key) -> Unit)?,
        deleteAll: (suspend () -> Unit)?
    ): BuilderWithSourceOfTruth<Key, Output, NewOutput> {
        return withSourceOfTruth(
            PersistentSourceOfTruth(
                realReader = reader,
                realWriter = writer,
                realDelete = delete,
                realDeleteAll = deleteAll
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
        delete: (suspend (Key) -> Unit)?,
        deleteAll: (suspend () -> Unit)?
    ): StoreBuilder<Key, NewOutput> = error("Multiple persisters are not supported")

    override fun <NewOutput : Any> nonFlowingPersister(
        reader: suspend (Key) -> NewOutput?,
        writer: suspend (Key, Output) -> Unit,
        delete: (suspend (Key) -> Unit)?,
        deleteAll: (suspend () -> Unit)?
    ): StoreBuilder<Key, NewOutput> = error("Multiple persisters are not supported")

    override fun nonFlowingPersisterLegacy(persister: Persister<Output, Key>): StoreBuilder<Key, Output> =
        error("Multiple persisters are not supported")
}
