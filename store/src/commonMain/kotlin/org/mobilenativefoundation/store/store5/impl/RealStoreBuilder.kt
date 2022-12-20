package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.MemoryPolicy
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.MutableStoreBuilder
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.StoreConverter
import org.mobilenativefoundation.store.store5.StoreDefaults
import org.mobilenativefoundation.store.store5.Updater

fun <Key : Any, CommonRepresentation : Any> storeBuilderFrom(
    fetcher: Fetcher<Key, *>,
    sourceOfTruth: SourceOfTruth<Key, *>? = null,
    updater: Updater<Key, CommonRepresentation, *>? = null
): StoreBuilder<Key, CommonRepresentation> = RealStoreBuilder(fetcher, updater = updater, sourceOfTruth = sourceOfTruth)

internal class RealStoreBuilder<Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any, SourceOfTruthRepresentation : Any, NetworkWriteResponse : Any>(
    private val fetcher: Fetcher<Key, NetworkRepresentation>,
    private val updater: Updater<Key, CommonRepresentation, NetworkWriteResponse>? = null,
    private val bookkeeper: Bookkeeper<Key>? = null,
    private val sourceOfTruth: SourceOfTruth<Key, SourceOfTruthRepresentation>? = null
) : MutableStoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse> {
    private var scope: CoroutineScope? = null
    private var cachePolicy: MemoryPolicy<Key, CommonRepresentation>? = StoreDefaults.memoryPolicy
    private var converter: StoreConverter<NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation>? = null

    override fun scope(scope: CoroutineScope): MutableStoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse> {
        this.scope = scope
        return this
    }

    override fun cachePolicy(memoryPolicy: MemoryPolicy<Key, CommonRepresentation>?): MutableStoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse> {
        cachePolicy = memoryPolicy
        return this
    }

    override fun disableCache(): MutableStoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse> {
        cachePolicy = null
        return this
    }

    override fun converter(converter: StoreConverter<NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation>): MutableStoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse> {
        this.converter = converter
        return this
    }

    override fun build(): MutableStore<Key, CommonRepresentation, NetworkWriteResponse> = RealStore(
        scope = scope ?: GlobalScope,
        sourceOfTruth = sourceOfTruth,
        fetcher = fetcher,
        updater = updater,
        memoryPolicy = cachePolicy,
        converter = converter,
        bookkeeper = bookkeeper
    )
}
