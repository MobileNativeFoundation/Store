package com.nytimes.android.external.store3

import com.nytimes.android.external.store3.base.Clearable
import com.nytimes.android.external.store3.base.Fetcher
import com.nytimes.android.external.store3.base.Parser
import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store3.base.impl.MemoryPolicy
import com.nytimes.android.external.store3.base.impl.StalePolicy
import com.nytimes.android.external.store3.base.impl.Store
import com.nytimes.android.external.store3.base.wrappers.cache
import com.nytimes.android.external.store3.base.wrappers.parser
import com.nytimes.android.external.store3.base.wrappers.persister
import com.nytimes.android.external.store3.pipeline.beginPipeline
import com.nytimes.android.external.store3.pipeline.open
import com.nytimes.android.external.store3.pipeline.withCache
import com.nytimes.android.external.store3.pipeline.withKeyConverter
import com.nytimes.android.external.store3.pipeline.withNonFlowPersister
import com.nytimes.android.external.store3.util.KeyParser
import com.nytimes.android.external.store4.RealInternalCoroutineStore
import com.nytimes.android.external.store4.SourceOfTruth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flow

data class TestStoreBuilder<Key, Output>(
    private val buildStore: () -> Store<Output, Key>,
    private val buildPipelineStore: () -> Store<out Output, Key>,
    private val builRealInternalCoroutineStore: () -> Store<Output, Key>
) {
    fun build(storeType: TestStoreType): Store<out Output, Key> = when (storeType) {
        TestStoreType.Store -> buildStore()
        TestStoreType.Pipeline -> buildPipelineStore()
        TestStoreType.CoroutineInternal -> builRealInternalCoroutineStore()
    }

    companion object {
        fun <Key, Output> from(
            scope: CoroutineScope,
            inflight: Boolean = true,
            fetcher: suspend (Key) -> Output
        ): TestStoreBuilder<Key, Output> = from(
            scope = scope,
            inflight = inflight,
            persister = null,
            fetchParser = null,
            fetcher = fetcher
        )

        fun <Key, Output> from(
            scope: CoroutineScope,
            inflight: Boolean = true,
            fetcher: suspend (Key) -> Output,
            fetchParser: KeyParser<Key, Output, Output>
        ): TestStoreBuilder<Key, Output> = from(
            scope = scope,
            inflight = inflight,
            persister = null,
            fetchParser = fetchParser,
            fetcher = fetcher
        )

        fun <Key, Output> from(
            scope: CoroutineScope,
            inflight: Boolean = true,
            fetcher: Fetcher<Output, Key>,
            fetchParser: Parser<Output, Output>,
            persister: Persister<Output, Key>
        ): TestStoreBuilder<Key, Output> = from(
            scope = scope,
            inflight = inflight,
            persister = persister,
            fetchParser = object : KeyParser<Key, Output, Output> {
                override suspend fun apply(key: Key, raw: Output): Output {
                    return fetchParser.apply(raw)
                }
            },
            fetcher = fetcher
        )

        fun <Key, Output> fromPostParser(
            scope: CoroutineScope,
            inflight: Boolean = true,
            fetcher: Fetcher<Output, Key>,
            postParser: Parser<Output, Output>,
            persister: Persister<Output, Key>
        ): TestStoreBuilder<Key, Output> = from(
            scope = scope,
            inflight = inflight,
            persister = persister,
            postParser = object : KeyParser<Key, Output, Output> {
                override suspend fun apply(key: Key, raw: Output): Output {
                    return postParser.apply(raw)
                }
            },
            fetcher = fetcher
        )

        @Suppress("UNCHECKED_CAST")
        fun <Key, Output> from(
            scope: CoroutineScope,
            inflight: Boolean = true,
            cached: Boolean = false,
            cacheMemoryPolicy: MemoryPolicy? = null,
            persister: Persister<Output, Key>? = null,
            persisterStalePolicy: StalePolicy = StalePolicy.UNSPECIFIED,
            fetchParser: KeyParser<Key, Output, Output>? = null,
            fetcher: suspend (Key) -> Output
        ): TestStoreBuilder<Key, Output> = from(
            scope = scope,
            inflight = inflight,
            cached = cached,
            cacheMemoryPolicy = cacheMemoryPolicy,
            persister = persister,
            persisterStalePolicy = persisterStalePolicy,
            fetchParser = fetchParser,
            fetcher = object : Fetcher<Output, Key> {
                override suspend fun fetch(key: Key): Output = fetcher(key)
            }
        )

        @Suppress("UNCHECKED_CAST")
        fun <Key, Output> from(
            scope: CoroutineScope,
            inflight: Boolean = true,
            cached: Boolean = false,
            cacheMemoryPolicy: MemoryPolicy? = null,
            persister: Persister<Output, Key>? = null,
            persisterStalePolicy: StalePolicy = StalePolicy.UNSPECIFIED,
            // parser that runs after fetch
            fetchParser: KeyParser<Key, Output, Output>? = null,
            // parser that runs after get from db
            postParser: KeyParser<Key, Output, Output>? = null,
            fetcher: Fetcher<Output, Key>
        ): TestStoreBuilder<Key, Output> {
            return TestStoreBuilder(
                buildStore = {
                    Store.from(
                        inflight = inflight,
                        f = fetcher
                    ).let {
                        if (fetchParser == null) {
                            it
                        } else {
                            it.parser(fetchParser)
                        }
                    }.let {
                        if (persister == null) {
                            it
                        } else {
                            it.persister(persister, persisterStalePolicy)
                        }
                    }.let {
                        if (postParser == null) {
                            it
                        } else {
                            it.parser(postParser)
                        }
                    }.let {
                        if (cached) {
                            it.cache(cacheMemoryPolicy)
                        } else {
                            it
                        }
                    }.open()
                },
                buildPipelineStore = {
                    beginPipeline<Key, Output>(
                        fetcher = {
                            flow {
                                emit(fetcher.fetch(it))
                            }
                        }
                    ).let {
                        if (fetchParser == null) {
                            it
                        } else {
                            it.withKeyConverter { key, oldOutput ->
                                fetchParser.apply(key, oldOutput)
                            }
                        }
                    }.let {
                        if (persister == null) {
                            it
                        } else {
                            it.withNonFlowPersister(
                                reader = {
                                    persister.read(it)
                                },
                                writer = { key, value ->
                                    persister.write(key, value)
                                },
                                delete = if (persister is Clearable<*>) {
                                    SuspendWrapper(
                                        (persister as Clearable<Key>)::clear
                                    )::apply
                                } else {
                                    null
                                }
                            )
                        }
                    }.let {
                        if (cached) {
                            it.withCache(cacheMemoryPolicy)
                        } else {
                            it
                        }
                    }.let {
                        if (postParser == null) {
                            it
                        } else {
                            it.withKeyConverter { key, oldOutput ->
                                postParser.apply(key, oldOutput)
                            }
                        }
                    }.open()
                },
                builRealInternalCoroutineStore = {
                    RealInternalCoroutineStore
                        .beginWithFlowingFetcher<Key, Output, Output> { key: Key ->
                            flow {
                                val value = fetcher.fetch(key = key)
                                if (fetchParser != null) {
                                    emit(fetchParser.apply(key, value))
                                } else {
                                    emit(value)
                                }
                            }
                        }
                        .scope(scope)
                        .let {
                            if (persister == null) {
                                it
                            } else {
                                it.sourceOfTruth(SourceOfTruth.fromLegacy(persister, postParser))
                            }
                        }
                        .let {
                            if (cached) {
                                it
                            } else {
                                it.disableCache()
                            }
                        }
                        .build().asLegacyStore()
                }
            )
        }
    }

    // wraps a regular fun to suspend, couldn't figure out how to create suspend fun variables :/
    private class SuspendWrapper<P0, R>(
        val f: (P0) -> R
    ) {
        suspend fun apply(input: P0): R = f(input)
    }
}

enum class TestStoreType {
    Store,
    Pipeline,
    CoroutineInternal
}
