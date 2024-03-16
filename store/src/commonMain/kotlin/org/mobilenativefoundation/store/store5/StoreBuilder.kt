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
package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.CoroutineScope
import org.mobilenativefoundation.store.cache5.Cache
import org.mobilenativefoundation.store.store5.impl.storeBuilderFromFetcher
import org.mobilenativefoundation.store.store5.impl.storeBuilderFromFetcherAndSourceOfTruth
import org.mobilenativefoundation.store.store5.impl.storeBuilderFromFetcherSourceOfTruthAndMemoryCache
import org.mobilenativefoundation.store.store5.impl.storeBuilderFromFetcherSourceOfTruthMemoryCacheAndConverter

/**
 * Main entry point for creating a [Store].
 */
interface StoreBuilder<Key : Any, Output : Any> {
    fun build(): Store<Key, Output>

    fun <Network : Any, Local : Any> toMutableStoreBuilder(
        converter: Converter<Network, Local, Output>,
    ): MutableStoreBuilder<Key, Network, Local, Output>

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
    fun cachePolicy(memoryPolicy: MemoryPolicy<Key, Output>?): StoreBuilder<Key, Output>

    /**
     * by default a Store caches in memory with a default policy of max items = 100
     */
    fun disableCache(): StoreBuilder<Key, Output>

    fun validator(validator: Validator<Output>): StoreBuilder<Key, Output>

    companion object {
        /**
         * Creates a new [StoreBuilder] from a [Fetcher].
         *
         * @param fetcher a [Fetcher] flow of network records.
         */
        fun <Key : Any, Input : Any> from(fetcher: Fetcher<Key, Input>): StoreBuilder<Key, Input> =
            storeBuilderFromFetcher(fetcher = fetcher)

        /**
         * Creates a new [StoreBuilder] from a [Fetcher] and a [SourceOfTruth].
         *
         * @param fetcher a function for fetching a flow of network records.
         * @param sourceOfTruth a [SourceOfTruth] for the store.
         */
        fun <Key : Any, Input : Any, Output : Any> from(
            fetcher: Fetcher<Key, Input>,
            sourceOfTruth: SourceOfTruth<Key, Input, Output>,
        ): StoreBuilder<Key, Output> = storeBuilderFromFetcherAndSourceOfTruth(fetcher = fetcher, sourceOfTruth = sourceOfTruth)

        fun <Key : Any, Network : Any, Output : Any> from(
            fetcher: Fetcher<Key, Network>,
            sourceOfTruth: SourceOfTruth<Key, Network, Output>,
            memoryCache: Cache<Key, Output>,
        ): StoreBuilder<Key, Output> =
            storeBuilderFromFetcherSourceOfTruthAndMemoryCache(
                fetcher,
                sourceOfTruth,
                memoryCache,
            )

        fun <Key : Any, Network : Any, Output : Any, Local : Any> from(
            fetcher: Fetcher<Key, Network>,
            sourceOfTruth: SourceOfTruth<Key, Local, Output>,
            converter: Converter<Network, Local, Output>,
        ): StoreBuilder<Key, Output> =
            storeBuilderFromFetcherSourceOfTruthMemoryCacheAndConverter(
                fetcher,
                sourceOfTruth,
                null,
                converter,
            )

        fun <Key : Any, Network : Any, Output : Any, Local : Any> from(
            fetcher: Fetcher<Key, Network>,
            sourceOfTruth: SourceOfTruth<Key, Local, Output>,
            memoryCache: Cache<Key, Output>,
            converter: Converter<Network, Local, Output>,
        ): StoreBuilder<Key, Output> =
            storeBuilderFromFetcherSourceOfTruthMemoryCacheAndConverter(
                fetcher,
                sourceOfTruth,
                memoryCache,
                converter,
            )
    }
}
