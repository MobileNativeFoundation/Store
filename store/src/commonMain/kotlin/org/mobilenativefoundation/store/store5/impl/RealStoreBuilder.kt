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

fun <Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any> storeBuilderFromFetcher(
    fetcher: Fetcher<Key, NetworkRepresentation>,
    sourceOfTruth: SourceOfTruth<Key, *>? = null,
): StoreBuilder<Key, NetworkRepresentation, CommonRepresentation, *> = RealStoreBuilder(fetcher, sourceOfTruth)

fun <Key : Any, CommonRepresentation : Any, NetworkRepresentation : Any, SourceOfTruthRepresentation : Any> storeBuilderFromFetcherAndSourceOfTruth(
    fetcher: Fetcher<Key, NetworkRepresentation>,
    sourceOfTruth: SourceOfTruth<Key, SourceOfTruthRepresentation>,
): StoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation> = RealStoreBuilder(fetcher, sourceOfTruth)

internal class RealStoreBuilder<Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any, SourceOfTruthRepresentation : Any>(
    private val fetcher: Fetcher<Key, NetworkRepresentation>,
    private val sourceOfTruth: SourceOfTruth<Key, SourceOfTruthRepresentation>? = null
) : StoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation> {
    private var scope: CoroutineScope? = null
    private var cachePolicy: MemoryPolicy<Key, CommonRepresentation>? = StoreDefaults.memoryPolicy
    private var converter: Converter<NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation>? = null

    override fun scope(scope: CoroutineScope): StoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation> {
        this.scope = scope
        return this
    }

    override fun cachePolicy(memoryPolicy: MemoryPolicy<Key, CommonRepresentation>?): StoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation> {
        cachePolicy = memoryPolicy
        return this
    }

    override fun disableCache(): StoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation> {
        cachePolicy = null
        return this
    }

    override fun converter(converter: Converter<NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation>): StoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation> {
        this.converter = converter
        return this
    }

    override fun build(): Store<Key, CommonRepresentation> = RealStore(
        scope = scope ?: GlobalScope,
        sourceOfTruth = sourceOfTruth,
        fetcher = fetcher,
        memoryPolicy = cachePolicy,
        converter = converter
    )

    override fun <NetworkWriteResponse : Any> build(
        updater: Updater<Key, CommonRepresentation, NetworkWriteResponse>,
        bookkeeper: Bookkeeper<Key>
    ): MutableStore<Key, CommonRepresentation> =
        build().asMutableStore<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse>(
            updater = updater,
            bookkeeper = bookkeeper
        )
}
