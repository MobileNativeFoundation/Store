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

import com.dropbox.android.external.store4.impl.RealStore
import com.dropbox.android.external.store4.impl.SourceOfTruth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.time.ExperimentalTime

/**
 * Main entry point for creating a [Store].
 */
@FlowPreview
@ExperimentalStdlibApi
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
     *  Example: MemoryPolicy.builder().setExpireAfterWrite(10.seconds).build()
     */
    @ExperimentalTime
    fun cachePolicy(memoryPolicy: MemoryPolicy?): StoreBuilder<Key, Output>

    /**
     * by default a Store caches in memory with a default policy of max items = 16
     */
    fun disableCache(): StoreBuilder<Key, Output>

    companion object {
        /**
         * Creates a new [StoreBuilder] from a non-[Flow] fetcher.
         *
         * Use when creating a [Store] that fetches objects in an HTTP-like single response per
         * request protocol.
         *
         * @param fetcher a function for fetching network records.
         */
        @OptIn(ExperimentalTime::class)
        fun <Key : Any, Output : Any> fromNonFlow(
            fetcher: suspend (key: Key) -> Output
        ): StoreBuilder<Key, Output> = from(
            fetcher = fetcher.asFlow()
        )

        /**
         * Creates a new [StoreBuilder] from a non-[Flow] fetcher and a [SourceOfTruth].
         *
         * Use when creating a [Store] that fetches objects in an HTTP-like single response per
         * request protocol.
         *
         * @param fetcher a function for fetching network records.
         * @param sourceOfTruth a [SourceOfTruth] for the store.
         */
        fun <Key : Any, Input : Any, Output : Any> fromNonFlow(
            fetcher: suspend (key: Key) -> Input,
            sourceOfTruth: SourceOfTruth<Key, Input, Output>
        ): StoreBuilder<Key, Output> = from(
            fetcher = fetcher.asFlow(),
            sourceOfTruth = sourceOfTruth
        )

        /**
         * Creates a new [StoreBuilder] from a non-[Flow] fetcher.
         *
         * Use when creating a [Store] that fetches objects in an HTTP-like single response per
         * request protocol.
         *
         * @param fetcher a function for fetching network records.
         * @param fetcherTransformer used to translate your fetcher's return value to success value
         * or error in the case that your fetcher does not communicate errors through exceptions
         */
        fun <Key : Any, RawOutput : Any, Output : Any> fromNonFlow(
            fetcher: suspend (key: Key) -> RawOutput,
            fetcherTransformer: (RawOutput) -> FetcherResult<Output>
        ): StoreBuilder<Key, Output> = from(
            fetcher = fetcher.asFlow(),
            fetcherTransformer = fetcherTransformer
        )

        /**
         * Creates a new [StoreBuilder] from a non-[Flow] fetcher.
         *
         * Use when creating a [Store] that fetches objects in an HTTP-like single response per
         * request protocol.
         *
         * @param fetcher a function for fetching network records.
         * @param fetcherTransformer used to translate your fetcher's return value to success value
         * or error in the case that your fetcher does not communicate errors through exceptions
         * @param sourceOfTruth a [SourceOfTruth] for the store.
         */
        fun <Key : Any, RawInput : Any, Input : Any, Output : Any> fromNonFlow(
            fetcher: suspend (key: Key) -> RawInput,
            fetcherTransformer: (RawInput) -> FetcherResult<Input>,
            sourceOfTruth: SourceOfTruth<Key, Input, Output>
        ): StoreBuilder<Key, Output> = from(
            fetcher = fetcher.asFlow(),
            fetcherTransformer = fetcherTransformer,
            sourceOfTruth = sourceOfTruth
        )

        private fun <Key, Value> (suspend (key: Key) -> Value).asFlow() = { key: Key ->
            flow {
                emit(invoke(key))
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
        @OptIn(ExperimentalTime::class)
        fun <Key : Any, Output : Any> from(
            fetcher: (key: Key) -> Flow<Output>
        ): StoreBuilder<Key, Output> = RealStoreBuilder(
            fetcher,
            fetcherTransformer = { FetcherResult.Data(it) }
        )

        /**
         * Creates a new [StoreBuilder] from a [Flow] fetcher.
         *
         * Use when creating a [Store] that fetches objects in an websocket-like multiple responses
         * per request protocol.
         *
         * @param fetcher a function for fetching a flow of network records.
         * @param sourceOfTruth a [SourceOfTruth] for the store.
         */
        fun <Key : Any, Input : Any, Output : Any> from(
            fetcher: (key: Key) -> Flow<Input>,
            sourceOfTruth: SourceOfTruth<Key, Input, Output>
        ): StoreBuilder<Key, Output> = RealStoreBuilder(
            fetcher = fetcher,
            fetcherTransformer = { FetcherResult.Data(it) },
            sourceOfTruth = sourceOfTruth
        )

        /**
         * Creates a new [StoreBuilder] from a [Flow] fetcher.
         *
         * Use when creating a [Store] that fetches objects in an websocket-like multiple responses
         * per request protocol.
         *
         * @param fetcher a function for fetching a flow of network records.
         * @param fetcherTransformer used to translate your fetcher's return value to success value
         * or error in the case that your fetcher does not communicate errors through exceptions
         */
        fun <Key : Any, RawOutput : Any, Output : Any> from(
            fetcher: (key: Key) -> Flow<RawOutput>,
            fetcherTransformer: (RawOutput) -> FetcherResult<Output>
        ): StoreBuilder<Key, Output> = RealStoreBuilder(
            fetcher = fetcher,
            fetcherTransformer = fetcherTransformer
        )

        /**
         * Creates a new [StoreBuilder] from a [Flow] fetcher.
         *
         * Use when creating a [Store] that fetches objects in an websocket-like multiple responses
         * per request protocol.
         *
         * @param fetcher a function for fetching a flow of network records.
         * @param fetcherTransformer used to translate your fetcher's return value to success value
         * or error in the case that your fetcher does not communicate errors through exceptions
         * @param sourceOfTruth a [SourceOfTruth] for the store.
         */
        fun <Key : Any, RawInput : Any, Input : Any, Output : Any> from(
            fetcher: (key: Key) -> Flow<RawInput>,
            fetcherTransformer: (RawInput) -> FetcherResult<Input>,
            sourceOfTruth: SourceOfTruth<Key, Input, Output>
        ): StoreBuilder<Key, Output> = RealStoreBuilder(
            fetcher = fetcher,
            fetcherTransformer = fetcherTransformer,
            sourceOfTruth = sourceOfTruth
        )
    }
}

@FlowPreview
@OptIn(ExperimentalTime::class)
@ExperimentalStdlibApi
@ExperimentalCoroutinesApi
private class RealStoreBuilder<Key : Any, RawInput : Any, Input : Any, Output : Any>(
    private val fetcher: (key: Key) -> Flow<RawInput>,
    private val fetcherTransformer: (RawInput) -> FetcherResult<Input>,
    private val sourceOfTruth: SourceOfTruth<Key, Input, Output>? = null
) : StoreBuilder<Key, Output> {
    private var scope: CoroutineScope? = null
    private var cachePolicy: MemoryPolicy? = StoreDefaults.memoryPolicy

    override fun scope(scope: CoroutineScope): RealStoreBuilder<Key, RawInput, Input, Output> {
        this.scope = scope
        return this
    }

    override fun cachePolicy(memoryPolicy: MemoryPolicy?): RealStoreBuilder<Key, RawInput, Input, Output> {
        cachePolicy = memoryPolicy
        return this
    }

    override fun disableCache(): RealStoreBuilder<Key, RawInput, Input, Output> {
        cachePolicy = null
        return this
    }

    override fun build(): Store<Key, Output> {
        @Suppress("UNCHECKED_CAST")
        return RealStore(
            scope = scope ?: GlobalScope,
            sourceOfTruth = sourceOfTruth,
            fetcher = { fetcher(it).map { fetcherTransformer(it) } },
            memoryPolicy = cachePolicy
        )
    }
}
