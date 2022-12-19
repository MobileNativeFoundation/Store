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
package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import org.mobilenativefoundation.store.cache5.CacheBuilder
import org.mobilenativefoundation.store.store5.CacheType
import org.mobilenativefoundation.store.store5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.MemoryPolicy
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreConverter
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import org.mobilenativefoundation.store.store5.StoreWriteResponse
import org.mobilenativefoundation.store.store5.impl.operators.Either
import org.mobilenativefoundation.store.store5.impl.operators.merge

internal class RealStore<Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any, SourceOfTruthRepresentation : Any, NetworkWriteResponse : Any>(
    scope: CoroutineScope,
    fetcher: Fetcher<Key, NetworkRepresentation>,
    sourceOfTruth: SourceOfTruth<Key, CommonRepresentation, SourceOfTruthRepresentation>? = null,
    converter: StoreConverter<NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation>? = null,
    private val memoryPolicy: MemoryPolicy<Key, CommonRepresentation>?
) : Store<Key, CommonRepresentation, NetworkWriteResponse> {
    /**
     * This source of truth is either a real database or an in memory source of truth created by
     * the builder.
     * Whatever is given, we always put a [SourceOfTruthWithBarrier] in front of it so that while
     * we write the value from fetcher into the disk, we can block reads to avoid sending new data
     * as if it came from the server (the [StoreReadResponse.origin] field).
     */
    private val sourceOfTruth: SourceOfTruthWithBarrier<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation>? =
        sourceOfTruth?.let {
            SourceOfTruthWithBarrier(it, converter)
        }

    private val memCache = memoryPolicy?.let {
        CacheBuilder<Key, CommonRepresentation>().apply {
            if (memoryPolicy.hasAccessPolicy) {
                expireAfterAccess(memoryPolicy.expireAfterAccess)
            }
            if (memoryPolicy.hasWritePolicy) {
                expireAfterWrite(memoryPolicy.expireAfterWrite)
            }
            if (memoryPolicy.hasMaxSize) {
                maximumSize(memoryPolicy.maxSize)
            }

            if (memoryPolicy.hasMaxWeight) {
                weigher(memoryPolicy.maxWeight) { key, value -> memoryPolicy.weigher.weigh(key, value) }
            }
        }.build()
    }

    /**
     * Fetcher controller maintains 1 and only 1 `Multicaster` for a given key to ensure network
     * requests are shared.
     */
    private val fetcherController = FetcherController(
        scope = scope,
        realFetcher = fetcher,
        sourceOfTruth = this.sourceOfTruth,
        converter = converter
    )

    override fun stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<CommonRepresentation>> =
        flow {
            val cachedToEmit = if (request.shouldSkipCache(CacheType.MEMORY)) {
                null
            } else {
                memCache?.getIfPresent(request.key)
            }

            cachedToEmit?.let {
                // if we read a value from cache, dispatch it first
                emit(StoreReadResponse.Data(value = it, origin = StoreReadResponseOrigin.Cache))
            }
            val stream = if (sourceOfTruth == null) {
                // piggypack only if not specified fresh data AND we emitted a value from the cache
                val piggybackOnly = !request.refresh && cachedToEmit != null
                @Suppress("UNCHECKED_CAST")

                createNetworkFlow(
                    request = request,
                    networkLock = null,
                    piggybackOnly = piggybackOnly
                ) as Flow<StoreReadResponse<CommonRepresentation>> // when no source of truth Input == Output
            } else {
                diskNetworkCombined(request, sourceOfTruth)
            }
            emitAll(
                stream.transform {
                    emit(it)
                    if (it is StoreReadResponse.NoNewData && cachedToEmit == null) {
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
                            emit(StoreReadResponse.Data(value = it, origin = StoreReadResponseOrigin.Cache))
                        }
                    }
                }
            )
        }.onEach {
            // whenever a value is dispatched, save it to the memory cache
            if (it.origin != StoreReadResponseOrigin.Cache) {
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
    override suspend fun clear() {
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
     * [StoreReadRequest.refresh] is set to `true`, we enable the fetcher flow.
     * This ensures we first get the value from disk and then load from server if necessary.
     */
    private fun diskNetworkCombined(
        request: StoreReadRequest<Key>,
        sourceOfTruth: SourceOfTruthWithBarrier<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation>
    ): Flow<StoreReadResponse<CommonRepresentation>> {
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
                    if (it.value is StoreReadResponse.Data || it.value is StoreReadResponse.NoNewData) {
                        // Unlocking disk only if network sent data or reported no new data
                        // so that fresh data request never receives new fetcher data after
                        // cached disk data.
                        // This means that if the user asked for fresh data but the network returned
                        // no new data we will still unblock disk.
                        diskLock.complete(Unit)
                    }

                    if (it.value !is StoreReadResponse.Data) {
                        emit(it.value.swapType())
                    }
                }

                is Either.Right -> {
                    // right, that is data from disk
                    when (val diskData = it.value) {
                        is StoreReadResponse.Data -> {
                            val diskValue = diskData.value
                            if (diskValue != null) {
                                @Suppress("UNCHECKED_CAST")
                                emit(diskData as StoreReadResponse<CommonRepresentation>)
                            }
                            // If the disk value is null or refresh was requested then allow fetcher
                            // to start emitting values.
                            if (request.refresh || diskData.value == null) {
                                networkLock.complete(Unit)
                            }
                        }

                        is StoreReadResponse.Error -> {
                            // disk sent an error, send it down as well
                            emit(diskData)

                            // If disk sent a read error, we should allow fetcher to start emitting
                            // values since there is nothing to read from disk. If disk sent a write
                            // error, we should NOT allow fetcher to start emitting values as we
                            // should always wait for the read attempt.
                            if (diskData is StoreReadResponse.Error.Exception &&
                                diskData.error is SourceOfTruth.ReadException
                            ) {
                                networkLock.complete(Unit)
                            }
                            // for other errors, don't do anything, wait for the read attempt
                        }

                        is StoreReadResponse.Loading,
                        is StoreReadResponse.NoNewData -> {
                        }
                    }
                }
            }
        }
    }

    private fun createNetworkFlow(
        request: StoreReadRequest<Key>,
        networkLock: CompletableDeferred<Unit>?,
        piggybackOnly: Boolean = false
    ): Flow<StoreReadResponse<NetworkRepresentation>> {
        return fetcherController
            .getFetcher(request.key, piggybackOnly)
            .onStart {
                // wait until disk gives us the go
                networkLock?.await()
                if (!piggybackOnly) {
                    emit(StoreReadResponse.Loading(origin = StoreReadResponseOrigin.Fetcher))
                }
            }
    }

    @ExperimentalStoreApi
    override fun stream(stream: Flow<StoreWriteRequest<Key, CommonRepresentation>>): Flow<StoreWriteResponse<NetworkWriteResponse>> {
        TODO("Not yet implemented")
    }

    @ExperimentalStoreApi
    override fun write(request: StoreWriteRequest<Key, CommonRepresentation>): StoreWriteResponse<NetworkWriteResponse> {
        TODO("Not yet implemented")
    }
}
