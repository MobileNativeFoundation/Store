package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.MemoryPolicy
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.StoreDefaults
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
internal class RealStoreBuilder<Key : Any, Input : Any, Output : Any>(
    private val fetcher: Fetcher<Key, Input>,
    private val sourceOfTruth: SourceOfTruth<Key, Input, Output>? = null
) : StoreBuilder<Key, Output> {
    private var scope: CoroutineScope? = null
    private var cachePolicy: MemoryPolicy<Key, Output>? = StoreDefaults.memoryPolicy

    override fun scope(scope: CoroutineScope): RealStoreBuilder<Key, Input, Output> {
        this.scope = scope
        return this
    }

    override fun cachePolicy(memoryPolicy: MemoryPolicy<Key, Output>?): RealStoreBuilder<Key, Input, Output> {
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
