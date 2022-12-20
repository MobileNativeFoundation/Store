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

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store.cache5.CacheBuilder
import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.CacheType
import org.mobilenativefoundation.store.store5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.MemoryPolicy
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StoreConverter
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import org.mobilenativefoundation.store.store5.StoreWriteResponse
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.UpdaterResult
import org.mobilenativefoundation.store.store5.impl.concurrent.AnyThread
import org.mobilenativefoundation.store.store5.impl.concurrent.ThreadSafety
import org.mobilenativefoundation.store.store5.impl.definition.WriteRequestQueue
import org.mobilenativefoundation.store.store5.impl.extensions.now
import org.mobilenativefoundation.store.store5.impl.operators.Either
import org.mobilenativefoundation.store.store5.impl.operators.merge

internal class RealStore<Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any, SourceOfTruthRepresentation : Any, NetworkWriteResponse : Any>(
    scope: CoroutineScope,
    fetcher: Fetcher<Key, NetworkRepresentation>,
    private val updater: Updater<Key, CommonRepresentation, NetworkWriteResponse>? = null,
    private val bookkeeper: Bookkeeper<Key>? = null,
    sourceOfTruth: SourceOfTruth<Key, SourceOfTruthRepresentation>? = null,
    converter: StoreConverter<NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation>? = null,
    private val memoryPolicy: MemoryPolicy<Key, CommonRepresentation>?
) : MutableStore<Key, CommonRepresentation, NetworkWriteResponse> {

    private val storeLock = Mutex()
    private val keyToWriteRequestQueue = mutableMapOf<Key, WriteRequestQueue<Key, CommonRepresentation, NetworkWriteResponse>>()
    private val keyToThreadSafety = mutableMapOf<Key, ThreadSafety>()

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
            safeInitStore(request.key)

            when (val eagerConflictResolutionResult = tryEagerlyResolveConflicts(request.key)) {
                is EagerConflictResolutionResult.Error.Exception -> {
                    logger.e(eagerConflictResolutionResult.error.toString())
                }

                is EagerConflictResolutionResult.Error.Message -> {
                    logger.w(eagerConflictResolutionResult.message)
                }

                is EagerConflictResolutionResult.Success.ConflictsResolved -> {
                    logger.d(eagerConflictResolutionResult.value.value.toString())
                }

                EagerConflictResolutionResult.Success.NoConflicts -> {
                    logger.d(eagerConflictResolutionResult.toString())
                }
            }

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
    override fun stream(stream: Flow<StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse>>): Flow<StoreWriteResponse<NetworkWriteResponse>> =
        flow {
            if (updater == null) {
                emit(StoreWriteResponse.Error.Message(NO_UPDATER))
            } else {
                stream
                    .onEach { writeRequest ->
                        safeInitStore(writeRequest.key)
                        addWriteRequestToQueue(writeRequest)
                    }
                    .collect { writeRequest ->
                        val storeWriteResponse = try {
                            sourceOfTruth?.write(writeRequest.key, writeRequest.input)
                            when (val updaterResult = tryUpdateServer(writeRequest)) {
                                is UpdaterResult.Error.Exception -> StoreWriteResponse.Error.Exception(updaterResult.error)
                                is UpdaterResult.Error.Message -> StoreWriteResponse.Error.Message(updaterResult.message)
                                is UpdaterResult.Success -> StoreWriteResponse.Success(updaterResult.value)
                            }
                        } catch (throwable: Throwable) {
                            StoreWriteResponse.Error.Exception(throwable)
                        }
                        emit(storeWriteResponse)
                    }
            }
        }

    @ExperimentalStoreApi
    override suspend fun write(request: StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse>): StoreWriteResponse<NetworkWriteResponse> =
        stream(flowOf(request)).first()

    private suspend fun tryUpdateServer(request: StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse>): UpdaterResult<NetworkWriteResponse> {
        val updaterResult = postLatest(request.key)
        if (updaterResult is UpdaterResult.Success<NetworkWriteResponse>) {
            updateWriteRequestQueue(
                key = request.key,
                created = request.created,
                updaterResult = updaterResult
            )
            bookkeeper?.clear(request.key)
        } else {
            bookkeeper?.setLastFailedSync(request.key)
        }

        return updaterResult
    }

    private suspend fun postLatest(key: Key): UpdaterResult<NetworkWriteResponse> {
        val writer = getLatestWriteRequest(key)
        return requireNotNull(updater).post(key, writer.input)
    }

    @AnyThread
    private suspend fun updateWriteRequestQueue(key: Key, created: Long, updaterResult: UpdaterResult.Success<NetworkWriteResponse>) {
        val nextWriteRequestQueue = withWriteRequestQueueLock(key) {
            val outstandingWriteRequests = ArrayDeque<StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse>>()

            for (writeRequest in this) {
                if (writeRequest.created <= created) {
                    requireNotNull(updater).onCompletion?.onSuccess?.invoke(updaterResult)
                    val storeWriteResponse = StoreWriteResponse.Success(updaterResult.value)
                    writeRequest.onCompletions?.forEach { onStoreWriteCompletion ->
                        onStoreWriteCompletion.onSuccess(storeWriteResponse)
                    }
                } else {
                    outstandingWriteRequests.add(writeRequest)
                }
            }
            outstandingWriteRequests
        }

        withThreadSafety(key) {
            keyToWriteRequestQueue[key] = nextWriteRequestQueue
        }
    }

    @AnyThread
    private suspend fun <Output : Any> withWriteRequestQueueLock(
        key: Key,
        block: suspend WriteRequestQueue<Key, CommonRepresentation, NetworkWriteResponse>.() -> Output
    ): Output =
        withThreadSafety(key) {
            writeRequests.lightswitch.lock(writeRequests.mutex)
            val writeRequestQueue = requireNotNull(keyToWriteRequestQueue[key])
            val output = writeRequestQueue.block()
            writeRequests.lightswitch.unlock(writeRequests.mutex)
            output
        }

    private suspend fun getLatestWriteRequest(key: Key): StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse> = withThreadSafety(key) {
        writeRequests.mutex.lock()
        val output = requireNotNull(keyToWriteRequestQueue[key]?.last())
        writeRequests.mutex.unlock()
        output
    }

    @AnyThread
    private suspend fun <Output : Any?> withThreadSafety(key: Key, block: suspend ThreadSafety.() -> Output): Output {
        storeLock.lock()
        val threadSafety = requireNotNull(keyToThreadSafety[key])
        val output = threadSafety.block()
        storeLock.unlock()
        return output
    }

    private suspend fun conflictsMightExist(key: Key): Boolean {
        val lastFailedSync = requireNotNull(bookkeeper).getLastFailedSync(key)
        return lastFailedSync != null || writeRequestsQueueIsEmpty(key).not()
    }

    private suspend fun latestOrNull(key: Key): CommonRepresentation? = fromMemCache(key) ?: fromSourceOfTruth(key)

    private suspend fun latest(key: Key): CommonRepresentation = requireNotNull(latestOrNull(key))

    private suspend fun fromSourceOfTruth(key: Key) = sourceOfTruth?.reader(key, CompletableDeferred(Unit))?.map { it.dataOrNull() }?.first()
    private fun fromMemCache(key: Key) = memCache?.getIfPresent(key)

    @AnyThread
    private suspend fun writeRequestsQueueIsEmpty(key: Key): Boolean = withThreadSafety(key) {
        keyToWriteRequestQueue[key].isNullOrEmpty()
    }

    private suspend fun addWriteRequestToQueue(writeRequest: StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse>) =
        withWriteRequestQueueLock(writeRequest.key) {
            add(writeRequest)
        }

    @AnyThread
    private suspend fun tryEagerlyResolveConflicts(key: Key): EagerConflictResolutionResult<NetworkWriteResponse> = withThreadSafety(key) {
        val latest = latestOrNull(key)
        when {
            bookkeeper == null || updater == null -> EagerConflictResolutionResult.Error.Message(NO_BOOKKEEPER_OR_UPDATER)
            latest == null || conflictsMightExist(key).not() -> EagerConflictResolutionResult.Success.NoConflicts
            else -> {
                try {
                    val updaterResult = updater.post(key, latest(key)).also { updaterResult ->
                        if (updaterResult is UpdaterResult.Success) {
                            updateWriteRequestQueue(key = key, created = now(), updaterResult = updaterResult)
                        }
                    }

                    when (updaterResult) {
                        is UpdaterResult.Error.Exception -> EagerConflictResolutionResult.Error.Exception(updaterResult.error)
                        is UpdaterResult.Error.Message -> EagerConflictResolutionResult.Error.Message(updaterResult.message)
                        is UpdaterResult.Success -> EagerConflictResolutionResult.Success.ConflictsResolved(updaterResult)
                    }
                } catch (throwable: Throwable) {
                    EagerConflictResolutionResult.Error.Exception(throwable)
                }
            }
        }
    }

    private suspend fun safeInitWriteRequestQueue(key: Key) = withThreadSafety(key) {
        if (keyToWriteRequestQueue[key] == null) {
            keyToWriteRequestQueue[key] = ArrayDeque()
        }
    }

    private suspend fun safeInitThreadSafety(key: Key) = storeLock.withLock {
        if (keyToThreadSafety[key] == null) {
            keyToThreadSafety[key] = ThreadSafety()
        }
    }

    private suspend fun safeInitStore(key: Key) {
        safeInitThreadSafety(key)
        safeInitWriteRequestQueue(key)
    }

    companion object {
        private const val NO_BOOKKEEPER_OR_UPDATER = "Bookkeeper and updater are required for eager conflict resolution"
        private const val NO_UPDATER = "Updater is required for writing to Store"
        private val logger = Logger.apply {
            setLogWriters(listOf(CommonWriter()))
            setTag("Store")
        }
    }
}

sealed class EagerConflictResolutionResult<out NetworkWriteResponse : Any> {

    sealed class Success<NetworkWriteResponse : Any> : EagerConflictResolutionResult<NetworkWriteResponse>() {
        object NoConflicts : Success<Nothing>()
        data class ConflictsResolved<NetworkWriteResponse : Any>(val value: UpdaterResult.Success<NetworkWriteResponse>) : Success<NetworkWriteResponse>()
    }

    sealed class Error : EagerConflictResolutionResult<Nothing>() {
        data class Message(val message: String) : Error()
        data class Exception(val error: Throwable) : Error()
    }
}
