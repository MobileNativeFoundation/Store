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

import com.dropbox.android.external.cache4.Cache
import com.dropbox.android.external.store4.CacheType
import com.dropbox.android.external.store4.MemoryPolicy
import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.android.external.store4.impl.operators.Either
import com.dropbox.android.external.store4.impl.operators.merge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.withIndex

@ExperimentalCoroutinesApi
@FlowPreview
internal class RealStore<Key : Any, Input : Any, Output : Any>(
    scope: CoroutineScope,
    fetcher: (Key) -> Flow<Input>,
    sourceOfTruth: SourceOfTruth<Key, Input, Output>? = null,
    private val memoryPolicy: MemoryPolicy?
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
        Cache.Builder.newBuilder().apply {
            if (memoryPolicy.hasAccessPolicy) {
                expireAfterAccess(memoryPolicy.expireAfterAccess, memoryPolicy.expireAfterTimeUnit)
            }
            if (memoryPolicy.hasWritePolicy) {
                expireAfterWrite(memoryPolicy.expireAfterWrite, memoryPolicy.expireAfterTimeUnit)
            }
            if (memoryPolicy.hasMaxSize) {
                maximumCacheSize(memoryPolicy.maxSize)
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

    override fun stream(request: StoreRequest<Key>): Flow<StoreResponse<Output>> {
        return if (sourceOfTruth == null) {
            @Suppress("UNCHECKED_CAST")
            createNetworkFlow(
                request = request,
                networkLock = null
            ) as Flow<StoreResponse<Output>> // when no source of truth Input == Output
        } else {
            diskNetworkCombined(request, sourceOfTruth)
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
                memCache?.get(request.key)?.let { cached ->
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
        request: StoreRequest<Key>,
        sourceOfTruth: SourceOfTruthWithBarrier<Key, Input, Output>
    ): Flow<StoreResponse<Output>> {
        val diskLock = CompletableDeferred<Unit>()
        val networkLock = CompletableDeferred<Unit>()
        val networkFlow = createNetworkFlow(request, networkLock)
        if (!request.shouldSkipCache(CacheType.DISK)) {
            diskLock.complete(Unit)
        }
        val diskFlow = sourceOfTruth.reader(request.key, diskLock)
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
    ): Flow<StoreResponse<Input>> {
        return fetcherController
            .getFetcher(request.key)
            .onStart {
                if (!request.shouldSkipCache(CacheType.DISK)) {
                    // wait until network gives us the go
                    networkLock?.await()
                }
                emit(
                    StoreResponse.Loading(
                        origin = ResponseOrigin.Fetcher
                    )
                )
            }
    }
}
