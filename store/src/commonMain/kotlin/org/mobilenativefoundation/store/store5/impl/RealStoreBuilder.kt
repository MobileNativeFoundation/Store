package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.Converter
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.MemoryPolicy
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.Processor
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StatefulStore
import org.mobilenativefoundation.store.store5.StatefulStoreKey
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.StoreDefaults
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.Validator
import org.mobilenativefoundation.store.store5.impl.extensions.asMutableStore

fun <Key : Any, Network : Any, Output : Any> storeBuilderFromFetcher(
    fetcher: Fetcher<Key, Network>,
    sourceOfTruth: SourceOfTruth<Key, *>? = null,
): StoreBuilder<Key, Network, Output, *> = RealStoreBuilder(fetcher, sourceOfTruth)

fun <Key : Any, Output : Any, Network : Any, Local : Any> storeBuilderFromFetcherAndSourceOfTruth(
    fetcher: Fetcher<Key, Network>,
    sourceOfTruth: SourceOfTruth<Key, Local>,
): StoreBuilder<Key, Network, Output, Local> = RealStoreBuilder(fetcher, sourceOfTruth)

internal class RealStoreBuilder<Key : Any, Network : Any, Output : Any, Local : Any>(
    private val fetcher: Fetcher<Key, Network>,
    private val sourceOfTruth: SourceOfTruth<Key, Local>? = null
) : StoreBuilder<Key, Network, Output, Local> {
    private var scope: CoroutineScope? = null
    private var cachePolicy: MemoryPolicy<Key, Output>? = StoreDefaults.memoryPolicy
    private var converter: Converter<Network, Output, Local>? = null
    private var validator: Validator<Output>? = null

    override fun scope(scope: CoroutineScope): StoreBuilder<Key, Network, Output, Local> {
        this.scope = scope
        return this
    }

    override fun cachePolicy(memoryPolicy: MemoryPolicy<Key, Output>?): StoreBuilder<Key, Network, Output, Local> {
        cachePolicy = memoryPolicy
        return this
    }

    override fun disableCache(): StoreBuilder<Key, Network, Output, Local> {
        cachePolicy = null
        return this
    }

    override fun validator(validator: Validator<Output>): StoreBuilder<Key, Network, Output, Local> {
        this.validator = validator
        return this
    }

    override fun converter(converter: Converter<Network, Output, Local>): StoreBuilder<Key, Network, Output, Local> {
        this.converter = converter
        return this
    }

    override fun build(processor: Processor<Output>?): Store<Key, Output> = RealStore(
        scope = scope ?: GlobalScope,
        sourceOfTruth = sourceOfTruth,
        fetcher = fetcher,
        memoryPolicy = cachePolicy,
        converter = converter,
        validator = validator,
        processor = processor
    )

    override fun build(processor: Processor<Output>): StatefulStore<StatefulStoreKey<Key>, Output> =
        build(processor)

    override fun <UpdaterResult : Any> build(
        updater: Updater<Key, Output, UpdaterResult>,
        bookkeeper: Bookkeeper<Key>
    ): MutableStore<Key, Output> =
        build().asMutableStore<Key, Network, Output, Local, UpdaterResult>(
            updater = updater,
            bookkeeper = bookkeeper
        )
}
