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

fun <Key : Any, Input : Any, Output : Any> storeBuilderFromFetcher(
    fetcher: Fetcher<Key, Input>,
    sourceOfTruth: SourceOfTruth<Key, *>? = null,
): StoreBuilder<Key, Output> = RealStoreBuilder(fetcher, sourceOfTruth)

fun <Key : Any, Input : Any, Output : Any> storeBuilderFromFetcherAndSourceOfTruth(
    fetcher: Fetcher<Key, Input>,
    sourceOfTruth: SourceOfTruth<Key, *>,
): StoreBuilder<Key, Output> = RealStoreBuilder(fetcher, sourceOfTruth)

fun <Key : Any, Network : Any, Output : Any, Local : Any> storeBuilderFromFetcherSourceOfTruthAndMemoryCache(
    fetcher: Fetcher<Key, Network>,
    sourceOfTruth: SourceOfTruth<Key, Local>,
    memoryCache: Cache<Key, Output>,
): StoreBuilder<Key, Output> = RealStoreBuilder(fetcher, sourceOfTruth, memoryCache)

internal class RealStoreBuilder<Key : Any, Network : Any, Output : Any, Local : Any>(
    private val fetcher: Fetcher<Key, Network>,
    private val sourceOfTruth: SourceOfTruth<Key, Local>? = null,
    private val memoryCache: Cache<Key, Output>? = null
) : StoreBuilder<Key, Output> {
    private var scope: CoroutineScope? = null
    private var cachePolicy: MemoryPolicy<Key, Output>? = StoreDefaults.memoryPolicy
    private var converter: Converter<Network, Output, Local>? = null
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

    override fun build(): Store<Key, Output> = RealStore(
        scope = scope ?: GlobalScope,
        sourceOfTruth = sourceOfTruth,
        fetcher = fetcher,
        converter = converter,
        validator = validator,
        memCache = memoryCache ?: cachePolicy?.let {
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
                    weigher(cachePolicy!!.maxWeight) { key, value -> cachePolicy!!.weigher.weigh(key, value) }
                }
            }.build()
        }
    )

    override fun <Network : Any, Local : Any> toMutableStoreBuilder(): MutableStoreBuilder<Key, Network, Output, Local> {
        fetcher as Fetcher<Key, Network>
        return if (sourceOfTruth == null && memoryCache == null) {
            mutableStoreBuilderFromFetcher(fetcher)
        } else if (memoryCache == null) {
            mutableStoreBuilderFromFetcherAndSourceOfTruth<Key, Network, Output, Local>(
                fetcher,
                sourceOfTruth as SourceOfTruth<Key, Local>
            )
        } else {
            mutableStoreBuilderFromFetcherSourceOfTruthAndMemoryCache(
                fetcher,
                sourceOfTruth as SourceOfTruth<Key, Local>,
                memoryCache
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
}
