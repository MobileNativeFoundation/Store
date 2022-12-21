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

@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.store5.impl

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.Clear
import org.mobilenativefoundation.store.store5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import org.mobilenativefoundation.store.store5.StoreWriteResponse
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.UpdaterResult
import org.mobilenativefoundation.store.store5.impl.extensions.now
import org.mobilenativefoundation.store.store5.internal.concurrent.AnyThread
import org.mobilenativefoundation.store.store5.internal.concurrent.ThreadSafety
import org.mobilenativefoundation.store.store5.internal.definition.WriteRequestQueue
import org.mobilenativefoundation.store.store5.internal.result.EagerConflictResolutionResult

internal class RealMutableStore<Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any, SourceOfTruthRepresentation : Any>(
    private val delegate: RealStore<Key, NetworkRepresentation, CommonRepresentation, SourceOfTruthRepresentation>,
    private val updater: Updater<Key, CommonRepresentation, *>,
    private val bookkeeper: Bookkeeper<Key>,
) : MutableStore<Key, CommonRepresentation>, Clear.Key<Key> by delegate, Clear.All by delegate {

    private val storeLock = Mutex()
    private val keyToWriteRequestQueue = mutableMapOf<Key, WriteRequestQueue<Key, CommonRepresentation, *>>()
    private val keyToThreadSafety = mutableMapOf<Key, ThreadSafety>()

    override fun <NetworkWriteResponse : Any> stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<CommonRepresentation>> =
        flow {
            safeInitStore(request.key)

            when (val eagerConflictResolutionResult = tryEagerlyResolveConflicts<NetworkWriteResponse>(request.key)) {
                is EagerConflictResolutionResult.Error.Exception -> {
                    logger.e(eagerConflictResolutionResult.error.toString())
                }

                is EagerConflictResolutionResult.Error.Message -> {
                    logger.e(eagerConflictResolutionResult.message)
                }

                is EagerConflictResolutionResult.Success.ConflictsResolved -> {
                    logger.d(eagerConflictResolutionResult.value.toString())
                }

                EagerConflictResolutionResult.Success.NoConflicts -> {
                    logger.d(eagerConflictResolutionResult.toString())
                }
            }

            delegate.stream(request).collect { storeReadResponse -> emit(storeReadResponse) }
        }

    @ExperimentalStoreApi
    override fun <NetworkWriteResponse : Any> stream(requestStream: Flow<StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse>>): Flow<StoreWriteResponse> =
        flow {
            requestStream
                .onEach { writeRequest ->
                    safeInitStore(writeRequest.key)
                    addWriteRequestToQueue(writeRequest)
                }
                .collect { writeRequest ->
                    val storeWriteResponse = try {
                        delegate.write(writeRequest.key, writeRequest.input)
                        when (val updaterResult = tryUpdateServer(writeRequest)) {
                            is UpdaterResult.Error.Exception -> StoreWriteResponse.Error.Exception(updaterResult.error)
                            is UpdaterResult.Error.Message -> StoreWriteResponse.Error.Message(updaterResult.message)
                            is UpdaterResult.Success.Typed<*> -> {
                                val typedValue = updaterResult.value as? NetworkWriteResponse
                                if (typedValue == null) {
                                    StoreWriteResponse.Success.Untyped(updaterResult.value)
                                } else {
                                    StoreWriteResponse.Success.Typed(updaterResult.value)
                                }
                            }

                            is UpdaterResult.Success.Untyped -> StoreWriteResponse.Success.Untyped(updaterResult.value)
                        }
                    } catch (throwable: Throwable) {
                        StoreWriteResponse.Error.Exception(throwable)
                    }
                    emit(storeWriteResponse)
                }
        }

    @ExperimentalStoreApi
    override suspend fun <NetworkWriteResponse : Any> write(request: StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse>): StoreWriteResponse =
        stream(flowOf(request)).first()

    private suspend fun <NetworkWriteResponse : Any> tryUpdateServer(request: StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse>): UpdaterResult {
        val updaterResult = postLatest<NetworkWriteResponse>(request.key)

        if (updaterResult is UpdaterResult.Success) {
            updateWriteRequestQueue<NetworkWriteResponse>(
                key = request.key,
                created = request.created,
                updaterResult = updaterResult
            )
            bookkeeper.clear(request.key)
        } else {
            bookkeeper.setLastFailedSync(request.key)
        }

        return updaterResult
    }

    private suspend fun <NetworkWriteResponse : Any> postLatest(key: Key): UpdaterResult {
        val writer = getLatestWriteRequest(key)
        return when (val updaterResult = updater.post(key, writer.input)) {
            is UpdaterResult.Error.Exception -> UpdaterResult.Error.Exception(updaterResult.error)
            is UpdaterResult.Error.Message -> UpdaterResult.Error.Message(updaterResult.message)
            is UpdaterResult.Success.Untyped -> UpdaterResult.Success.Untyped(updaterResult.value)
            is UpdaterResult.Success.Typed<*> -> {
                val typedValue = updaterResult.value as? NetworkWriteResponse
                if (typedValue == null) {
                    UpdaterResult.Success.Untyped(updaterResult.value)
                } else {
                    UpdaterResult.Success.Typed(updaterResult.value)
                }
            }
        }
    }

    @AnyThread
    private suspend fun <NetworkWriteResponse : Any> updateWriteRequestQueue(key: Key, created: Long, updaterResult: UpdaterResult.Success) {
        val nextWriteRequestQueue = withWriteRequestQueueLock<ArrayDeque<StoreWriteRequest<Key, CommonRepresentation, *>>, NetworkWriteResponse>(key) {
            val outstandingWriteRequests = ArrayDeque<StoreWriteRequest<Key, CommonRepresentation, *>>()

            for (writeRequest in this) {
                if (writeRequest.created <= created) {
                    updater.onCompletion?.onSuccess?.invoke(updaterResult)

                    val storeWriteResponse = when (updaterResult) {
                        is UpdaterResult.Success.Typed<*> -> {
                            val typedValue = updaterResult.value as? NetworkWriteResponse
                            if (typedValue == null) {
                                StoreWriteResponse.Success.Untyped(updaterResult.value)
                            } else {
                                StoreWriteResponse.Success.Typed(updaterResult.value)
                            }
                        }

                        is UpdaterResult.Success.Untyped -> StoreWriteResponse.Success.Untyped(updaterResult.value)
                    }

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
    private suspend fun <Output : Any, NetworkWriteResponse : Any> withWriteRequestQueueLock(
        key: Key,
        block: suspend WriteRequestQueue<Key, CommonRepresentation, *>.() -> Output
    ): Output =
        withThreadSafety(key) {
            writeRequests.lightswitch.lock(writeRequests.mutex)
            val writeRequestQueue = requireNotNull(keyToWriteRequestQueue[key])
            val output = writeRequestQueue.block()
            writeRequests.lightswitch.unlock(writeRequests.mutex)
            output
        }

    private suspend fun getLatestWriteRequest(key: Key): StoreWriteRequest<Key, CommonRepresentation, *> = withThreadSafety(key) {
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
        val lastFailedSync = bookkeeper.getLastFailedSync(key)
        return lastFailedSync != null || writeRequestsQueueIsEmpty(key).not()
    }

    @AnyThread
    private suspend fun writeRequestsQueueIsEmpty(key: Key): Boolean = withThreadSafety(key) {
        keyToWriteRequestQueue[key].isNullOrEmpty()
    }

    private suspend fun <NetworkWriteResponse : Any> addWriteRequestToQueue(writeRequest: StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse>) =
        withWriteRequestQueueLock<Unit, NetworkWriteResponse>(writeRequest.key) {
            add(writeRequest)
        }

    @AnyThread
    private suspend fun <NetworkWriteResponse : Any> tryEagerlyResolveConflicts(key: Key): EagerConflictResolutionResult<NetworkWriteResponse> =
        withThreadSafety(key) {
            val latest = delegate.latestOrNull(key)
            when {
                latest == null || conflictsMightExist(key).not() -> EagerConflictResolutionResult.Success.NoConflicts
                else -> {
                    try {
                        val updaterResult = updater.post(key, latest).also { updaterResult ->
                            if (updaterResult is UpdaterResult.Success) {
                                updateWriteRequestQueue<NetworkWriteResponse>(key = key, created = now(), updaterResult = updaterResult)
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
        private val logger = Logger.apply {
            setLogWriters(listOf(CommonWriter()))
            setTag("Store")
        }
        private const val UNKNOWN_ERROR = "Unknown error occurred"
    }
}
