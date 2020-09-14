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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlin.time.ExperimentalTime

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
     *  Example: MemoryPolicy.builder().setExpireAfterWrite(10.seconds).build()
     */
    @ExperimentalTime
    fun cachePolicy(memoryPolicy: MemoryPolicy<Key, Output>?): StoreBuilder<Key, Output>

    /**
     * by default a Store caches in memory with a default policy of max items = 100
     */
    fun disableCache(): StoreBuilder<Key, Output>

    companion object {

        /**
         * Creates a new [StoreBuilder] from a [Fetcher].
         *
         * @param fetcher a [Fetcher] flow of network records.
         */
        @OptIn(ExperimentalTime::class)
        fun <Key : Any, Output : Any> from(
            fetcher: Fetcher<Key, Output>
        ): StoreBuilder<Key, Output> = RealStoreBuilder(fetcher)

        /**
         * Creates a new [StoreBuilder] from a [Fetcher] and a [SourceOfTruth].
         *
         * @param fetcher a function for fetching a flow of network records.
         * @param sourceOfTruth a [SourceOfTruth] for the store.
         */
        fun <Key : Any, Input : Any, Output : Any> from(
            fetcher: Fetcher<Key, Input>,
            sourceOfTruth: SourceOfTruth<Key, Input, Output>
        ): StoreBuilder<Key, Output> = RealStoreBuilder(
            fetcher = fetcher,
            sourceOfTruth = sourceOfTruth
        )
    }
}

@FlowPreview
@OptIn(ExperimentalTime::class)
@ExperimentalCoroutinesApi
private class RealStoreBuilder<Key : Any, Input : Any, Output : Any>(
    private val fetcher: Fetcher<Key, Input>,
    private val sourceOfTruth: SourceOfTruth<Key, Input, Output>? = null
) : StoreBuilder<Key, Output> {
    private var scope: CoroutineScope? = null
    private var cachePolicy: MemoryPolicy<Key, Output>? = StoreDefaults.memoryPolicy

    override fun scope(scope: CoroutineScope): RealStoreBuilder<Key, Input, Output> {
        this.scope = scope
        return this
    }

    override fun cachePolicy(memoryPolicy: MemoryPolicy<Key, Output>?):
        RealStoreBuilder<Key, Input, Output> {
            cachePolicy = memoryPolicy
            return this
        }

    override fun disableCache(): RealStoreBuilder<Key, Input, Output> {
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
}
