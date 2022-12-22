package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.Converter
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.MemoryPolicy
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.StoreDefaults
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.impl.extensions.asMutableStore

fun <Key : Any, Network : Any, Common : Any> storeBuilderFromFetcher(
    fetcher: Fetcher<Key, Network>,
    sourceOfTruth: SourceOfTruth<Key, *>? = null,
): StoreBuilder<Key, Network, Common, *> = RealStoreBuilder(fetcher, sourceOfTruth)

fun <Key : Any, Common : Any, Network : Any, SOT : Any> storeBuilderFromFetcherAndSourceOfTruth(
    fetcher: Fetcher<Key, Network>,
    sourceOfTruth: SourceOfTruth<Key, SOT>,
): StoreBuilder<Key, Network, Common, SOT> = RealStoreBuilder(fetcher, sourceOfTruth)

internal class RealStoreBuilder<Key : Any, Network : Any, Common : Any, SOT : Any>(
    private val fetcher: Fetcher<Key, Network>,
    private val sourceOfTruth: SourceOfTruth<Key, SOT>? = null
) : StoreBuilder<Key, Network, Common, SOT> {
    private var scope: CoroutineScope? = null
    private var cachePolicy: MemoryPolicy<Key, Common>? = StoreDefaults.memoryPolicy
    private var converter: Converter<Network, Common, SOT>? = null

    override fun scope(scope: CoroutineScope): StoreBuilder<Key, Network, Common, SOT> {
        this.scope = scope
        return this
    }

    override fun cachePolicy(memoryPolicy: MemoryPolicy<Key, Common>?): StoreBuilder<Key, Network, Common, SOT> {
        cachePolicy = memoryPolicy
        return this
    }

    override fun disableCache(): StoreBuilder<Key, Network, Common, SOT> {
        cachePolicy = null
        return this
    }

    override fun converter(converter: Converter<Network, Common, SOT>): StoreBuilder<Key, Network, Common, SOT> {
        this.converter = converter
        return this
    }

    override fun build(): Store<Key, Common> = RealStore(
        scope = scope ?: GlobalScope,
        sourceOfTruth = sourceOfTruth,
        fetcher = fetcher,
        memoryPolicy = cachePolicy,
        converter = converter
    )

    override fun <UpdaterResult : Any> build(
        updater: Updater<Key, Common, UpdaterResult>,
        bookkeeper: Bookkeeper<Key>
    ): MutableStore<Key, Common> =
        build().asMutableStore<Key, Network, Common, SOT, UpdaterResult>(
            updater = updater,
            bookkeeper = bookkeeper
        )
}
