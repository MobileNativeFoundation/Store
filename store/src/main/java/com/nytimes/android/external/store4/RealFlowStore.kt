package com.nytimes.android.external.store4

import com.com.nytimes.suspendCache.StoreCache
import com.nytimes.android.external.store3.base.impl.MemoryPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@ExperimentalCoroutinesApi
@FlowPreview
class RealFlowStore<Key, Input, Output>(
    private val scope: CoroutineScope,
    private val fetcher: (Key) -> Flow<Input>,
    sourceOfTruth: SourceOfTruth<Key, Input, Output>? = null,
    private val memoryPolicy: MemoryPolicy?
) : FlowStore<Key, Output> {
    /**
     * This source of truth is either a real database or an in memory source of truth created by
     * the builder.
     * Whatever is given, we always put a [SourceOfTruthWithBarrier] in front of it so that while
     * we write the value from fetcher into the disk, we can block reads to avoid sending new data
     * as if it came from the server (the [StoreResponse.origin] field).
     */
    private val sourceOfTruth: SourceOfTruthWithBarrier<Key, Input, Output>? =
        sourceOfTruth?.let {
            SourceOfTruthWithBarrier(it)
        }
    private val memCache = memoryPolicy?.let {
        StoreCache.fromRequest<Key, Output?, StoreRequest<Key>>(
            loader = {
                TODO(
                    """
                    This should've never been called. We don't need this anymore, should remove
                    loader after we clean old Store ?
                """.trimIndent()
                )
            },
            memoryPolicy = memoryPolicy
        )
    }
    /**
     * Fetcher controller maintains 1 and only 1 `Multiplexer` for a given key to ensure network
     * requests are shared.
     */
    private val fetcherController = FetcherController(
        scope = scope,
        realFetcher = fetcher,
        sourceOfTruth = this.sourceOfTruth
    )

    override fun stream(request: StoreRequest<Key>): Flow<StoreResponse<Output>> {
        return if (sourceOfTruth == null) {
            createNetworkFlow(
                request = request,
                networkLock = null
            )
        } else {
            diskNetworkCombined(request)
        }.onEach {
            // whenever a value is dispatched, save it to the memory cache
            if (it.origin != ResponseOrigin.Cache) {
                it.dataOrNull()?.let { data ->
                    memCache?.put(request.key, data)
                }
            }
        }.onStart {
            // if there is anything cached, dispatch it first if requested
            if (!request.shouldSkipCache(CacheType.MEMORY)) {
                memCache?.getIfPresent(request.key)?.let { cached ->
                    emit(StoreResponse.Data(value = cached, origin = ResponseOrigin.Cache))
                }
            }
        }
    }

    override suspend fun clear(key: Key) {
        memCache?.invalidate(key)
        sourceOfTruth?.delete(key)
    }

    /**
     * We want to stream from disk but also want to refresh. If requested or necessary.
     *
     * How it works:
     * There are two flows:
     * Fetcher: The flow we get for the fetching
     * Disk: The flow we get from the [SourceOfTruth].
     * Both flows are controlled by a lock for each so that we can start the right one based on
     * the request status or values we receive.
     *
     * Value is always returned from [SourceOfTruth] while the errors are dispatched from both the
     * `Fetcher` and [SourceOfTruth].
     *
     * There are two initialization paths:
     *
     * 1) Request wants to skip disk cache:
     * In this case, we first start the fetcher flow. When fetcher flow provides something besides
     * an error, we enable the disk flow.
     *
     * 2) Request does not want to skip disk cache:
     * In this case, we first start the disk flow. If disk flow returns `null` or
     * [StoreRequest.refresh] is set to `true`, we enable the fetcher flow.
     * This ensures we first get the value from disk and then load from server if necessary.
     */
    private fun diskNetworkCombined(
        request: StoreRequest<Key>
    ): Flow<StoreResponse<Output>> {
        val diskLock = CompletableDeferred<Unit>()
        val networkLock = CompletableDeferred<Unit>()
        val networkFlow = createNetworkFlow(request, networkLock)
        if (!request.shouldSkipCache(CacheType.DISK)) {
            diskLock.complete(Unit)
        }
        val diskFlow = sourceOfTruth!!.reader(request.key, diskLock)
        // we use a merge implementation that gives the source of the flow so that we can decide
        // based on that.
        return networkFlow.merge(diskFlow.withIndex())
            .transform {
                // left is Fetcher while right is source of truth
                if (it is Either.Left) {
                    if (it.value !is StoreResponse.Data<*>) {
                        emit(it.value.swapType())
                    }
                    // network sent something
                    if (it.value is StoreResponse.Data<*>) {
                        // unlocking disk only if network sent data so that fresh data request never
                        // receives disk data by mistake
                        diskLock.complete(Unit)
                    }
                } else if (it is Either.Right) {
                    // right, that is data from disk
                    val (index, diskData) = it.value
                    if (diskData.value != null) {
                        emit(
                            StoreResponse.Data(
                                value = diskData.value,
                                origin = diskData.origin
                            ) as StoreResponse<Output>
                        )
                    }

                    // if this is the first disk value and it is null, we should enable fetcher
                    // TODO should we ignore the index and always enable?
                    if (index == 0 && (diskData.value == null || request.refresh)) {
                        networkLock.complete(Unit)
                    }
                }
            }
    }

    private fun createNetworkFlow(
            request: StoreRequest<Key>,
            networkLock: CompletableDeferred<Unit>?
    ): Flow<StoreResponse<Output>> {
        return fetcherController
            .getFetcher(request.key)
            .map {
                StoreResponse.Data(
                    value = it,
                    origin = ResponseOrigin.Fetcher
                ) as StoreResponse<Input>
            }.catch {
                emit(
                    StoreResponse.Error(
                        error = it,
                        origin = ResponseOrigin.Fetcher
                    )
                )
            }.onStart {
                if (!request.shouldSkipCache(CacheType.DISK)) {
                    // wait until network gives us the go
                    networkLock?.await()
                }
                emit(
                    StoreResponse.Loading(
                        origin = ResponseOrigin.Fetcher
                    )
                )
            }.map {
                it.swapType<Output>()
            }
    }

    fun asLegacyStore() = open()

    // TODO this builder w/ 3 type args is really ugly, think more about it...
    companion object {
        fun <Key, Input, Output> fromNonFlow(
            fetcher: suspend (key: Key) -> Input
        ) = Builder<Key, Input, Output> { key: Key ->
            flow {
                emit(fetcher(key))
            }
        }

        fun <Key, Input, Output> from(
            fetcher: (key: Key) -> Flow<Input>
        ) = Builder<Key, Input, Output>(fetcher)
    }

    class Builder<Key, Input, Output>(
        private val fetcher: (key: Key) -> Flow<Input>
    ) {
        private var scope: CoroutineScope? = null
        private var sourceOfTruth: SourceOfTruth<Key, Input, Output>? = null
        private var cachePolicy: MemoryPolicy? = StoreDefaults.memoryPolicy

        fun scope(scope: CoroutineScope): Builder<Key, Input, Output> {
            this.scope = scope
            return this
        }

        fun nonFlowingPersister(
            reader: suspend (Key) -> Output?,
            writer: suspend (Key, Input) -> Unit,
            delete: (suspend (Key) -> Unit)? = null
        ): Builder<Key, Input, Output> {
            sourceOfTruth = PersistentNonFlowingSourceOfTruth(
                realReader = reader,
                realWriter = writer,
                realDelete = delete
            )
            return this
        }

        fun persister(
            reader: (Key) -> Flow<Output?>,
            writer: suspend (Key, Input) -> Unit,
            delete: (suspend (Key) -> Unit)? = null
        ): Builder<Key, Input, Output> {
            sourceOfTruth = PersistentSourceOfTruth(
                realReader = reader,
                realWriter = writer,
                realDelete = delete
            )
            return this
        }

        fun sourceOfTruth(
            sourceOfTruth: SourceOfTruth<Key, Input, Output>
        ): Builder<Key, Input, Output> {
            this.sourceOfTruth = sourceOfTruth
            return this
        }

        fun cachePolicy(memoryPolicy: MemoryPolicy?): Builder<Key, Input, Output> {
            cachePolicy = memoryPolicy
            return this
        }

        fun disableCache(): Builder<Key, Input, Output> {
            cachePolicy = null
            return this
        }

        fun build(): RealFlowStore<Key, Input, Output> {
            @Suppress("UNCHECKED_CAST")
            return RealFlowStore(
                scope = scope ?: GlobalScope,
                sourceOfTruth = sourceOfTruth,
                fetcher = fetcher,
                memoryPolicy = cachePolicy
            )
        }
    }
}
