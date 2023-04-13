@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
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

internal class RealStoreBuilder<Key : Any, Network : Any, Output : Any, Local : Any>(
    private val fetcher: Fetcher<Key, Network>,
    private val sourceOfTruth: SourceOfTruth<Key, Local>? = null
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
        memoryPolicy = cachePolicy,
        converter = converter,
        validator = validator
    )

    override fun <Network : Any, Local : Any> toMutableStoreBuilder(): MutableStoreBuilder<Key, Network, Output, Local> {
        fetcher as Fetcher<Key, Network>
        return if (sourceOfTruth == null) {
            mutableStoreBuilderFromFetcher(fetcher)
        } else {
            mutableStoreBuilderFromFetcherAndSourceOfTruth<Key, Network, Output, Local>(fetcher, sourceOfTruth as SourceOfTruth<Key, Local>)
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
