package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import org.mobilenativefoundation.store.cache5.Cache
import org.mobilenativefoundation.store.cache5.CacheBuilder
import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.Converter
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.MemoryPolicy
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.MutableStoreBuilder
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreDefaults
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.Validator
import org.mobilenativefoundation.store.store5.impl.extensions.asMutableStore

//we don't have a source of truth and can use a dummy converter
fun <Key : Any, Network : Any, Local : Any, Output : Any> mutableStoreBuilderFromFetcher(
    fetcher: Fetcher<Key, Network>,
    converter: Converter<Network, Local, Output>
): MutableStoreBuilder<Key, Network, Local, Output> = RealMutableStoreBuilder(fetcher, converter = converter)

fun <Key : Any, Network : Any, Local : Any, Output : Any> mutableStoreBuilderFromFetcherAndSourceOfTruth(
    fetcher: Fetcher<Key, Network>,
    sourceOfTruth: SourceOfTruth<Key, Local, Output>,
    converter: Converter<Network, Local, Output>
): MutableStoreBuilder<Key, Network, Local, Output> = RealMutableStoreBuilder(fetcher, sourceOfTruth, converter = converter)

fun <Key : Any, Network : Any, Output : Any, Local : Any> mutableStoreBuilderFromFetcherSourceOfTruthAndMemoryCache(
    fetcher: Fetcher<Key, Network>,
    sourceOfTruth: SourceOfTruth<Key, Local, Output>,
    memoryCache: Cache<Key, Output>,
    converter: Converter<Network, Local,Output>,
): MutableStoreBuilder<Key, Network, Local, Output> = RealMutableStoreBuilder(fetcher, sourceOfTruth, memoryCache, converter = converter)

internal class RealMutableStoreBuilder<Key : Any, Network : Any, Local : Any, Output : Any>(
    private val fetcher: Fetcher<Key, Network>,
    private val sourceOfTruth: SourceOfTruth<Key, Local, Output>? = null,
    private val memoryCache: Cache<Key, Output>? = null,
    private val converter: Converter<Network, Local, Output>
) : MutableStoreBuilder<Key, Network, Local, Output> {
    private var scope: CoroutineScope? = null
    private var cachePolicy: MemoryPolicy<Key, Output>? = StoreDefaults.memoryPolicy
    private var validator: Validator<Output>? = null

    override fun scope(scope: CoroutineScope): MutableStoreBuilder<Key, Network, Local, Output> {
        this.scope = scope
        return this
    }

    override fun cachePolicy(memoryPolicy: MemoryPolicy<Key, Output>?): MutableStoreBuilder<Key, Network, Local, Output> {
        cachePolicy = memoryPolicy
        return this
    }

    override fun disableCache(): MutableStoreBuilder<Key, Network, Local, Output> {
        cachePolicy = null
        return this
    }

    override fun validator(validator: Validator<Output>): MutableStoreBuilder<Key, Network, Local, Output> {
        this.validator = validator
        return this
    }

    fun build(): Store<Key, Output> = RealStore(
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

    override fun <UpdaterResult : Any> build(
        updater: Updater<Key, Output, UpdaterResult>,
        bookkeeper: Bookkeeper<Key>?
    ): MutableStore<Key, Output> =
        build().asMutableStore<Key, Network, Output, Local, UpdaterResult>(
            updater = updater,
            bookkeeper = bookkeeper
        )
}
