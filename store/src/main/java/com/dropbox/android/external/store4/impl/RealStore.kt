/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.CacheType
import com.dropbox.android.external.store4.ExperimentalStoreApi
import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.MemoryPolicy
import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.SourceOfTruth
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.android.external.store4.impl.operators.Either
import com.dropbox.android.external.store4.impl.operators.merge
import com.nytimes.android.external.cache3.CacheBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import java.util.concurrent.TimeUnit
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime

@ExperimentalTime
@ExperimentalCoroutinesApi
@FlowPreview
internal class RealStore<Key : Any, Input : Any, Output : Any>(
    scope: CoroutineScope,
    fetcher: Fetcher<Key, Input>,
    sourceOfTruth: SourceOfTruth<Key, Input, Output>? = null,
    private val memoryPolicy: MemoryPolicy<Key, Output>?
) : Store<Key, Output> {
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
        CacheBuilder.newBuilder().apply {
            if (memoryPolicy.hasAccessPolicy) {
                expireAfterAccess(
                    memoryPolicy.expireAfterAccess.toLong(DurationUnit.MILLISECONDS),
                    TimeUnit.MILLISECONDS
                )
            }
            if (memoryPolicy.hasWritePolicy) {
                expireAfterWrite(
                    memoryPolicy.expireAfterWrite.toLong(DurationUnit.MILLISECONDS),
                    TimeUnit.MILLISECONDS
                )
            }
            if (memoryPolicy.hasMaxSize) {
                maximumSize(memoryPolicy.maxSize)
            }

            if (memoryPolicy.hasMaxWeight) {
                maximumWeight(memoryPolicy.maxWeight)
                weigher { k: Key, v: Output -> memoryPolicy.weigher.weigh(k, v) }
            }
        }.build<Key, Output>()
    }

    /**
     * Fetcher controller maintains 1 and only 1 `Multicaster` for a given key to ensure network
     * requests are shared.
     */
    private val fetcherController = FetcherController(
        scope = scope,
        realFetcher = fetcher,
        sourceOfTruth = this.sourceOfTruth
    )

    override fun stream(request: StoreRequest<Key>): Flow<StoreResponse<Output>> =
        flow<StoreResponse<Output>> {
            val cachedToEmit = if (request.shouldSkipCache(CacheType.MEMORY)) {
                null
            } else {
                memCache?.getIfPresent(request.key)
            }

            cachedToEmit?.let {
                // if we read a value from cache, dispatch it first
                emit(StoreResponse.Data(value = it, origin = ResponseOrigin.Cache))
            }
            val stream = if (sourceOfTruth == null) {
                // piggypack only if not specified fresh data AND we emitted a value from the cache
                val piggybackOnly = !request.refresh && cachedToEmit != null
                @Suppress("UNCHECKED_CAST")

                createNetworkFlow(
                    request = request,
                    networkLock = null,
                    piggybackOnly = piggybackOnly
                ) as Flow<StoreResponse<Output>> // when no source of truth Input == Output
            } else {
                diskNetworkCombined(request, sourceOfTruth)
            }
            emitAll(
                stream.transform {
                    emit(it)
                    if (it is StoreResponse.NoNewData && cachedToEmit == null) {
                        // In the special case where fetcher returned no new data we actually want to
                        // serve cache data (even if the request specified skipping cache and/or SoT)
                        //
                        // For stream(Request.cached(key, refresh=true)) we will return:
                        // Cache
                        // Source of truth
                        // Fetcher - > Loading
                        // Fetcher - > NoNewData
                        // (future Source of truth updates)
                        //
                        // For stream(Request.fresh(key)) we will return:
                        // Fetcher - > Loading
                        // Fetcher - > NoNewData
                        // Cache
                        // Source of truth
                        // (future Source of truth updates)
                        memCache?.getIfPresent(request.key)?.let {
                            emit(StoreResponse.Data(value = it, origin = ResponseOrigin.Cache))
                        }
                    }
                }
            )
        }.onEach {
            // whenever a value is dispatched, save it to the memory cache
            if (it.origin != ResponseOrigin.Cache) {
                it.dataOrNull()?.let { data ->
                    memCache?.put(request.key, data)
                }
            }
        }

    override suspend fun clear(key: Key) {
        memCache?.invalidate(key)
        sourceOfTruth?.delete(key)
    }

    @ExperimentalStoreApi
    override suspend fun clearAll() {
        memCache?.invalidateAll()
        sourceOfTruth?.deleteAll()
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
        request: StoreRequest<Key>,
        sourceOfTruth: SourceOfTruthWithBarrier<Key, Input, Output>
    ): Flow<StoreResponse<Output>> {
        val diskLock = CompletableDeferred<Unit>()
        val networkLock = CompletableDeferred<Unit>()
        val networkFlow = createNetworkFlow(request, networkLock)
        val skipDiskCache = request.shouldSkipCache(CacheType.DISK)
        if (!skipDiskCache) {
            diskLock.complete(Unit)
        }
        val diskFlow = sourceOfTruth.reader(request.key, diskLock).onStart {
            // wait for disk to latch first to ensure it happens before network triggers.
            // after that, if we'll not read from disk, then allow network to continue
            if (skipDiskCache) {
                networkLock.complete(Unit)
            }
        }
        // we use a merge implementation that gives the source of the flow so that we can decide
        // based on that.
        return networkFlow.merge(diskFlow).transform {
            // left is Fetcher while right is source of truth
            when (it) {
                is Either.Left -> {
                    // left, that is data from network
                    if (it.value is StoreResponse.Data || it.value is StoreResponse.NoNewData) {
                        // Unlocking disk only if network sent data or reported no new data
                        // so that fresh data request never receives new fetcher data after
                        // cached disk data.
                        // This means that if the user asked for fresh data but the network returned
                        // no new data we will still unblock disk.
                        diskLock.complete(Unit)
                    }

                    if (it.value !is StoreResponse.Data) {
                        emit(it.value.swapType<Output>())
                    }
                }
                is Either.Right -> {
                    // right, that is data from disk
                    when (val diskData = it.value) {
                        is StoreResponse.Data -> {
                            val diskValue = diskData.value
                            if (diskValue != null) {
                                @Suppress("UNCHECKED_CAST")
                                emit(diskData as StoreResponse<Output>)
                            }
                            // If the disk value is null or refresh was requested then allow fetcher
                            // to start emitting values.
                            if (request.refresh || diskData.value == null) {
                                networkLock.complete(Unit)
                            }
                        }
                        is StoreResponse.Error -> {
                            // disk sent an error, send it down as well
                            emit(diskData)

                            // If disk sent a read error, we should allow fetcher to start emitting
                            // values since there is nothing to read from disk. If disk sent a write
                            // error, we should NOT allow fetcher to start emitting values as we
                            // should always wait for the read attempt.
                            if (diskData is StoreResponse.Error.Exception &&
                                diskData.error is SourceOfTruth.ReadException
                            ) {
                                networkLock.complete(Unit)
                            }
                            // for other errors, don't do anything, wait for the read attempt
                        }
                    }
                }
            }
        }
    }

    private fun createNetworkFlow(
        request: StoreRequest<Key>,
        networkLock: CompletableDeferred<Unit>?,
        piggybackOnly: Boolean = false
    ): Flow<StoreResponse<Input>> {
        return fetcherController
            .getFetcher(request.key, piggybackOnly)
            .onStart {
                // wait until disk gives us the go
                networkLock?.await()
                if (!piggybackOnly) {
                    emit(StoreResponse.Loading(origin = ResponseOrigin.Fetcher))
                }
            }
    }
}
