package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.CoroutineScope
import org.mobilenativefoundation.store.store5.impl.mutableStoreBuilderFromFetcherAndSourceOfTruth

interface MutableStoreBuilder<Key : Any, Network : Any, Local : Any, Output : Any> {

    fun <Response : Any> build(
        updater: Updater<Key, Output, Response>,
        bookkeeper: Bookkeeper<Key>? = null
    ): MutableStore<Key, Output>

    /**
     * A store multicasts same [Output] value to many consumers (Similar to RxJava.share()), by default
     *  [Store] will open a global scope for management of shared responses, if instead you'd like to control
     *  the scope that sharing/multicasting happens in you can pass a @param [scope]
     *
     *   @param scope - scope to use for sharing
     */
    fun scope(scope: CoroutineScope): MutableStoreBuilder<Key, Network, Local, Output>

    /**
     * controls eviction policy for a store cache, use [MemoryPolicy.MemoryPolicyBuilder] to configure a TTL
     *  or size based eviction
     *  Example: MemoryPolicy.builder().setExpireAfterWrite(10.seconds).build()
     */
    fun cachePolicy(memoryPolicy: MemoryPolicy<Key, Output>?): MutableStoreBuilder<Key, Network, Local, Output>

    /**
     * by default a Store caches in memory with a default policy of max items = 100
     */
    fun disableCache(): MutableStoreBuilder<Key, Network, Local, Output>

    fun validator(validator: Validator<Output>): MutableStoreBuilder<Key, Network,Local, Output, >

    companion object {
        /**
         * Creates a new [MutableStoreBuilder] from a [Fetcher] and a [SourceOfTruth].
         *
         * @param fetcher a function for fetching a flow of network records.
         * @param sourceOfTruth a [SourceOfTruth] for the store.
         */
        fun <Key : Any, Network : Any,Local : Any, Output : Any, > from(
            fetcher: Fetcher<Key, Network>,
            sourceOfTruth: SourceOfTruth<Key, Local, Output>,
            converter: Converter<Network, Local, Output>
        ): MutableStoreBuilder<Key, Network, Local, Output> =
            mutableStoreBuilderFromFetcherAndSourceOfTruth(
                fetcher = fetcher, sourceOfTruth = sourceOfTruth,
                converter = converter
            )
    }
}
