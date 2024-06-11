@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import org.mobilenativefoundation.store.cache5.Cache
import org.mobilenativefoundation.store.cache5.CacheBuilder
import org.mobilenativefoundation.store.store5.Converter
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.MemoryPolicy
import org.mobilenativefoundation.store.store5.MutableStoreBuilder
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.StoreDefaults
import org.mobilenativefoundation.store.store5.Validator

fun <Key : Any, Network : Any, Output : Any> storeBuilderFromFetcher(
    fetcher: Fetcher<Key, Network>,
    sourceOfTruth: SourceOfTruth<Key, Network, Output>? = null,
): StoreBuilder<Key, Output> = RealStoreBuilder<Key, Network, Output, Network>(fetcher, sourceOfTruth)

fun <Key : Any, Network : Any, Output : Any> storeBuilderFromFetcherAndSourceOfTruth(
    fetcher: Fetcher<Key, Network>,
    sourceOfTruth: SourceOfTruth<Key, Network, Output>,
): StoreBuilder<Key, Output> = RealStoreBuilder<Key, Network, Output, Network>(fetcher, sourceOfTruth)

fun <Key : Any, Network : Any, Output : Any> storeBuilderFromFetcherSourceOfTruthAndMemoryCache(
    fetcher: Fetcher<Key, Network>,
    sourceOfTruth: SourceOfTruth<Key, Network, Output>,
    memoryCache: Cache<Key, Output>,
): StoreBuilder<Key, Output> = RealStoreBuilder<Key, Network, Output, Network>(fetcher, sourceOfTruth, memoryCache)

fun <Key : Any, Network : Any, Output : Any, Local : Any> storeBuilderFromFetcherSourceOfTruthMemoryCacheAndConverter(
    fetcher: Fetcher<Key, Network>,
    sourceOfTruth: SourceOfTruth<Key, Local, Output>,
    memoryCache: Cache<Key, Output>?,
    converter: Converter<Network, Local, Output>,
): StoreBuilder<Key, Output> = RealStoreBuilder(fetcher, sourceOfTruth, memoryCache, converter)

internal class RealStoreBuilder<Key : Any, Network : Any, Output : Any, Local : Any>(
    private val fetcher: Fetcher<Key, Network>,
    private val sourceOfTruth: SourceOfTruth<Key, Local, Output>? = null,
    private val memoryCache: Cache<Key, Output>? = null,
    private val converter: Converter<Network, Local, Output>? = null,
) : StoreBuilder<Key, Output> {
    private var scope: CoroutineScope? = null
    private var cachePolicy: MemoryPolicy<Key, Output>? = StoreDefaults.memoryPolicy
    private var validator: Validator<Output>? = null

    override fun scope(scope: CoroutineScope): StoreBuilder<Key, Output> {
        this.scope = scope
        return this
    }

    override fun cachePolicy(memoryPolicy: MemoryPolicy<Key, Output>?): StoreBuilder<Key, Output> {
        cachePolicy = memoryPolicy
        return this
    }

    override fun disableCache(): StoreBuilder<Key, Output> {
        cachePolicy = null
        return this
    }

    override fun validator(validator: Validator<Output>): StoreBuilder<Key, Output> {
        this.validator = validator
        return this
    }

    override fun build(): Store<Key, Output> =
        RealStore<Key, Network, Output, Local>(
            scope = scope ?: GlobalScope,
            sourceOfTruth = sourceOfTruth,
            fetcher = fetcher,
            converter = converter ?: defaultConverter(),
            validator = validator,
            memCache =
                memoryCache ?: cachePolicy?.let {
                    CacheBuilder<Key, Output>().apply {
                        if (cachePolicy!!.hasAccessPolicy) {
                            expireAfterAccess(cachePolicy!!.expireAfterAccess)
                        }
                        if (cachePolicy!!.hasWritePolicy) {
                            expireAfterWrite(cachePolicy!!.expireAfterWrite)
                        }
                        if (cachePolicy!!.hasMaxSize) {
                            maximumSize(cachePolicy!!.maxSize)
                        }

                        if (cachePolicy!!.hasMaxWeight) {
                            weigher(cachePolicy!!.maxWeight) { key, value ->
                                cachePolicy!!.weigher.weigh(
                                    key,
                                    value,
                                )
                            }
                        }
                    }.build()
                },
        )

    override fun <Network : Any, Local : Any> toMutableStoreBuilder(
        converter: Converter<Network, Local, Output>,
    ): MutableStoreBuilder<Key, Network, Local, Output> {
        fetcher as Fetcher<Key, Network>
        return if (sourceOfTruth == null && memoryCache == null) {
            mutableStoreBuilderFromFetcher(fetcher, converter)
        } else if (memoryCache == null) {
            mutableStoreBuilderFromFetcherAndSourceOfTruth(
                fetcher,
                sourceOfTruth as SourceOfTruth<Key, Local, Output>,
                converter,
            )
        } else {
            mutableStoreBuilderFromFetcherSourceOfTruthAndMemoryCache(
                fetcher,
                sourceOfTruth as SourceOfTruth<Key, Local, Output>,
                memoryCache,
                converter,
            )
        }.apply {
            if (this@RealStoreBuilder.scope != null) {
                scope(this@RealStoreBuilder.scope!!)
            }

            if (this@RealStoreBuilder.cachePolicy != null) {
                cachePolicy(this@RealStoreBuilder.cachePolicy)
            }

            if (this@RealStoreBuilder.validator != null) {
                validator(this@RealStoreBuilder.validator!!)
            }
        }
    }

    private fun <Network : Any, Local : Any, Output : Any> defaultConverter() =
        object : Converter<Network, Local, Output> {
            override fun fromOutputToLocal(output: Output): Local =
                throw IllegalStateException("non mutable store never call this function")

            override fun fromNetworkToLocal(network: Network): Local = network as Local
        }
}
