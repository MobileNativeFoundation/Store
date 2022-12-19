package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.MemoryPolicy
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.StoreConverter
import org.mobilenativefoundation.store.store5.StoreDefaults

internal class RealStoreBuilder<Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any, SourceOfTruthRepresentation : Any, NetworkWriteResponse : Any>(
    private val fetcher: Fetcher<Key, NetworkRepresentation>,
    private val sourceOfTruth: SourceOfTruth<Key, SourceOfTruthRepresentation>? = null
) : StoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse> {
    private var scope: CoroutineScope? = null
    private var cachePolicy: MemoryPolicy<Key, CommonRepresentation>? = StoreDefaults.memoryPolicy
    private var converter: StoreConverter<NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation>? = null

    override fun scope(scope: CoroutineScope): RealStoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse> {
        this.scope = scope
        return this
    }

    override fun cachePolicy(memoryPolicy: MemoryPolicy<Key, CommonRepresentation>?): RealStoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse> {
        cachePolicy = memoryPolicy
        return this
    }

    override fun disableCache(): RealStoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse> {
        cachePolicy = null
        return this
    }

    override fun converter(converter: StoreConverter<NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation>): StoreBuilder<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation, NetworkWriteResponse> {
        this.converter = converter
        return this
    }

    override fun build(): Store<Key, CommonRepresentation, NetworkWriteResponse> = RealStore(
        scope = scope ?: GlobalScope,
        sourceOfTruth = sourceOfTruth,
        fetcher = fetcher,
        memoryPolicy = cachePolicy,
        converter = converter
    )
}
