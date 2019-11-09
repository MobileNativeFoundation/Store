package com.nytimes.android.external.store4.impl

import com.nytimes.android.external.store4.ResponseOrigin
import com.nytimes.android.external.store4.StoreResponse
import com.nytimes.android.external.store4.impl.multiplex.Multiplexer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*

/**
 * This class maintains one and only 1 fetcher for a given [Key].
 *
 * Any value emitted by the fetcher is sent into the [sourceOfTruth] before it is dispatched.
 * If [sourceOfTruth] is `null`, [enablePiggyback] is set to true by default so that previous
 * fetcher requests receives values dispatched by later requests even if they don't share the
 * request.
 */
@FlowPreview
@ExperimentalCoroutinesApi
internal class FetcherController<Key, Input, Output>(
        /**
         * The [CoroutineScope] to use when collecting from the fetcher
         */
        private val scope: CoroutineScope,
        /**
         * The function that provides the actualy fetcher flow when needed
         */
        private val realFetcher: (Key) -> Flow<Input>,
        /**
         * [SourceOfTruth] to send the data each time fetcher dispatches a value. Can be `null` if
         * no [SourceOfTruth] is available.
         */
        private val sourceOfTruth: SourceOfTruthWithBarrier<Key, Input, Output>?,
        /**
         * When enabled, downstream collectors are never closed, instead, they are kept active to
         * receive values dispatched by fetchers created after them. This makes [FetcherController]
         * act like a [SourceOfTruth] in the lack of a [SourceOfTruth] provided by the developer.
         */
        private val enablePiggyback: Boolean = sourceOfTruth == null
) {
    private val fetchers = RefCountedResource<Key, Multiplexer<StoreResponse<Input>>>(
            create = { key: Key ->
                Multiplexer(
                        scope = scope,
                        bufferSize = 0,
                        source = {
                            realFetcher(key).map {
                                StoreResponse.Data(
                                        it,
                                        origin = ResponseOrigin.Fetcher
                                ) as StoreResponse<Input>
                            }.catch {
                                emit(StoreResponse.Error(it, origin = ResponseOrigin.Fetcher))
                            }
                        },
                        piggybackingDownstream = enablePiggyback,
                        onEach = {
                            it.dataOrNull()?.let {
                                sourceOfTruth?.write(key, it)
                            }
                        }
                )
            },
            onRelease = { key: Key, multiplexer: Multiplexer<StoreResponse<Input>> ->
                multiplexer.close()
            }
    )

    fun getFetcher(key: Key): Flow<StoreResponse<Input>> {
        return flow {
            val fetcher = fetchers.acquire(key)
            try {
                emitAll(fetcher.create())
            } finally {
                fetchers.release(key, fetcher)
            }
        }
    }

    // visible for testing
    internal suspend fun fetcherSize() = fetchers.size()
}
