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
import org.mobilenativefoundation.store.store5.impl.RealStoreBuilder

/**
 * Main entry point for creating a [Store].
 */
interface StoreBuilder<Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any, SourceOfTruthRepresentation : Any, NetworkWriteResponse : Any> {
    fun build(): Store<Key, CommonRepresentation, NetworkWriteResponse>

    /**
     * A store multicasts same [CommonRepresentation] value to many consumers (Similar to RxJava.share()), by default
     *  [Store] will open a global scope for management of shared responses, if instead you'd like to control
     *  the scope that sharing/multicasting happens in you can pass a @param [scope]
     *
     *   @param scope - scope to use for sharing
     */
    fun scope(scope: CoroutineScope): StoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse>

    /**
     * controls eviction policy for a store cache, use [MemoryPolicy.MemoryPolicyBuilder] to configure a TTL
     *  or size based eviction
     *  Example: MemoryPolicy.builder().setExpireAfterWrite(10.seconds).build()
     */
    fun cachePolicy(memoryPolicy: MemoryPolicy<Key, CommonRepresentation>?): StoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse>

    /**
     * by default a Store caches in memory with a default policy of max items = 100
     */
    fun disableCache(): StoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse>

    fun converter(converter: StoreConverter<NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation>): StoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse>

    companion object {

        /**
         * Creates a new [StoreBuilder] from a [Fetcher].
         *
         * @param fetcher a [Fetcher] flow of network records.
         */
        fun <Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any, SourceOfTruthRepresentation : Any, NetworkWriteResponse : Any> from(
            fetcher: Fetcher<Key, NetworkRepresentation>
        ): StoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse> = RealStoreBuilder(fetcher)

        /**
         * Creates a new [StoreBuilder] from a [Fetcher] and a [SourceOfTruth].
         *
         * @param fetcher a function for fetching a flow of network records.
         * @param sourceOfTruth a [SourceOfTruth] for the store.
         */
        fun <Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any, SourceOfTruthRepresentation : Any, NetworkWriteResponse : Any> from(
            fetcher: Fetcher<Key, NetworkRepresentation>,
            sourceOfTruth: SourceOfTruth<Key, CommonRepresentation, SourceOfTruthRepresentation>
        ): StoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse> = RealStoreBuilder(
            fetcher = fetcher,
            sourceOfTruth = sourceOfTruth
        )
    }
}
