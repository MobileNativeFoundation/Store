package com.nytimes.android.external.store4.impl

import com.nytimes.android.external.store4.impl.multiplex.Multiplexer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * This class maintains one and only 1 fetcher for a given [Key].
 */
@FlowPreview
@ExperimentalCoroutinesApi
internal class FetcherController<Key, Input, Output>(
    private val scope: CoroutineScope,
    private val realFetcher: (Key) -> Flow<Input>,
    private val sourceOfTruth: SourceOfTruthWithBarrier<Key, Input, Output>?
) {
    private val fetchers = RefCountedResource(
            create = { key: Key ->
                Multiplexer(
                        scope = scope,
                        bufferSize = 0,
                        source = {
                            realFetcher(key)
                        },
                        onEach = {
                            sourceOfTruth?.write(key, it)
                        }
                )
            },
            onRelease = { key: Key, multiplexer: Multiplexer<Input> ->
                multiplexer.close()
            }
    )

    fun getFetcher(key: Key): Flow<Input> {
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
