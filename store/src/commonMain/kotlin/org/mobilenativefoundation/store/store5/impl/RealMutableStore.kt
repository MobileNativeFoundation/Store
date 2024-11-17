@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.Clear
import org.mobilenativefoundation.store.store5.Logger
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

@OptIn(ExperimentalStoreApi::class)
internal class RealMutableStore<Key : Any, Network : Any, Output : Any, Local : Any>(
    private val delegate: RealStore<Key, Network, Output, Local>,
    private val updater: Updater<Key, Output, *>,
    private val bookkeeper: Bookkeeper<Key>?,
    private val logger: Logger = DefaultLogger()
) : MutableStore<Key, Output>, Clear.Key<Key> by delegate, Clear.All by delegate {
    private val storeLock = Mutex()
    private val keyToWriteRequestQueue = mutableMapOf<Key, WriteRequestQueue<Key, Output, *>>()
    private val keyToThreadSafety = mutableMapOf<Key, ThreadSafety>()

    override fun <Response : Any> stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<Output>> =
        flow {
            safeInitStore(request.key)

            when (val eagerConflictResolutionResult = tryEagerlyResolveConflicts<Response>(request.key)) {

                // TODO(matt-ramotar): Many use cases will not want to pull immediately after failing
                // to push local changes. We should enable configuration of conflict resolution strategies,
                // such as logging, retrying, canceling.

                is EagerConflictResolutionResult.Error.Exception -> {
                    logger.error(eagerConflictResolutionResult.error.toString())
                }

                is EagerConflictResolutionResult.Error.Message -> {
                    logger.error(eagerConflictResolutionResult.message)
                }

                is EagerConflictResolutionResult.Success.ConflictsResolved -> {
                    logger.debug(eagerConflictResolutionResult.value.toString())
                }

                EagerConflictResolutionResult.Success.NoConflicts -> {
                    logger.debug(eagerConflictResolutionResult.toString())
                }
            }

            delegate.stream(request).collect { storeReadResponse -> emit(storeReadResponse) }
        }

    @ExperimentalStoreApi
    override fun <Response : Any> stream(requestStream: Flow<StoreWriteRequest<Key, Output, Response>>): Flow<StoreWriteResponse> =
        flow {
            requestStream
                .onEach { writeRequest ->
                    safeInitStore(writeRequest.key)
                    addWriteRequestToQueue(writeRequest)
                }
                .collect { writeRequest ->
                    val storeWriteResponse =
                        try {
                            delegate.write(writeRequest.key, writeRequest.value)
                            when (val updaterResult = tryUpdateServer(writeRequest)) {
                                is UpdaterResult.Error.Exception -> StoreWriteResponse.Error.Exception(updaterResult.error)
                                is UpdaterResult.Error.Message -> StoreWriteResponse.Error.Message(updaterResult.message)
                                is UpdaterResult.Success.Typed<*> -> {
                                    val typedValue = updaterResult.value as? Response
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
    override suspend fun <Response : Any> write(request: StoreWriteRequest<Key, Output, Response>): StoreWriteResponse =
        stream(flowOf(request)).first()

    private suspend fun <Response : Any> tryUpdateServer(request: StoreWriteRequest<Key, Output, Response>): UpdaterResult {
        val updaterResult = postLatest<Response>(request.key)

        if (updaterResult is UpdaterResult.Success) {
            updateWriteRequestQueue<Response>(
                key = request.key,
                created = request.created,
                updaterResult = updaterResult,
            )
            bookkeeper?.clear(request.key)
        } else {
            bookkeeper?.setLastFailedSync(request.key)
        }

        return updaterResult
    }

    private suspend fun <Response : Any> postLatest(key: Key): UpdaterResult {
        val writer = getLatestWriteRequest(key)
        return when (val updaterResult = updater.post(key, writer.value)) {
            is UpdaterResult.Error.Exception -> UpdaterResult.Error.Exception(updaterResult.error)
            is UpdaterResult.Error.Message -> UpdaterResult.Error.Message(updaterResult.message)
            is UpdaterResult.Success.Untyped -> UpdaterResult.Success.Untyped(updaterResult.value)
            is UpdaterResult.Success.Typed<*> -> {
                val typedValue = updaterResult.value as? Response
                if (typedValue == null) {
                    UpdaterResult.Success.Untyped(updaterResult.value)
                } else {
                    UpdaterResult.Success.Typed(updaterResult.value)
                }
            }
        }
    }

    @AnyThread
    private suspend fun <Response : Any> updateWriteRequestQueue(
        key: Key,
        created: Long,
        updaterResult: UpdaterResult.Success,
    ) {
        val nextWriteRequestQueue =
            withWriteRequestQueueLock(key) {
                val outstandingWriteRequests = ArrayDeque<StoreWriteRequest<Key, Output, *>>()

                for (writeRequest in this) {
                    if (writeRequest.created <= created) {
                        updater.onCompletion?.onSuccess?.invoke(updaterResult)

                        val storeWriteResponse =
                            when (updaterResult) {
                                is UpdaterResult.Success.Typed<*> -> {
                                    val typedValue = updaterResult.value as? Response
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
    private suspend fun <Result : Any> withWriteRequestQueueLock(
        key: Key,
        block: suspend WriteRequestQueue<Key, Output, *>.() -> Result,
    ): Result =
        withThreadSafety(key) {
            writeRequests.lightswitch.lock(writeRequests.mutex)
            val writeRequestQueue = requireNotNull(keyToWriteRequestQueue[key])
            val output = writeRequestQueue.block()
            writeRequests.lightswitch.unlock(writeRequests.mutex)
            output
        }

    private suspend fun getLatestWriteRequest(key: Key): StoreWriteRequest<Key, Output, *> =
        withThreadSafety(key) {
            writeRequests.mutex.lock()
            val output = requireNotNull(keyToWriteRequestQueue[key]?.last())
            writeRequests.mutex.unlock()
            output
        }

    @AnyThread
    private suspend fun <Output : Any?> withThreadSafety(
        key: Key,
        block: suspend ThreadSafety.() -> Output,
    ): Output =
        storeLock.withLock {
            val threadSafety = requireNotNull(keyToThreadSafety[key])
            threadSafety.block()
        }

    private suspend fun conflictsMightExist(key: Key): Boolean {
        val lastFailedSync = bookkeeper?.getLastFailedSync(key)
        return lastFailedSync != null || writeRequestsQueueIsEmpty(key).not()
    }

    private fun writeRequestsQueueIsEmpty(key: Key): Boolean = keyToWriteRequestQueue[key].isNullOrEmpty()

    private suspend fun <Response : Any> addWriteRequestToQueue(writeRequest: StoreWriteRequest<Key, Output, Response>) =
        withWriteRequestQueueLock(writeRequest.key) {
            add(writeRequest)
        }

    @AnyThread
    private suspend fun <Response : Any> tryEagerlyResolveConflicts(key: Key): EagerConflictResolutionResult<Response> =
        withThreadSafety(key) {
            val latest = delegate.latestOrNull(key)
            when {
                latest == null || bookkeeper == null || conflictsMightExist(key).not() -> EagerConflictResolutionResult.Success.NoConflicts
                else -> {
                    try {
                        val updaterResult =
                            updater.post(key, latest).also { updaterResult ->
                                if (updaterResult is UpdaterResult.Success) {
                                    updateWriteRequestQueue<Response>(key = key, created = now(), updaterResult = updaterResult)
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

    private suspend fun safeInitWriteRequestQueue(key: Key) =
        withThreadSafety(key) {
            if (keyToWriteRequestQueue[key] == null) {
                keyToWriteRequestQueue[key] = ArrayDeque()
            }
        }

    private suspend fun safeInitThreadSafety(key: Key) =
        storeLock.withLock {
            if (keyToThreadSafety[key] == null) {
                keyToThreadSafety[key] = ThreadSafety()
            }
        }

    private suspend fun safeInitStore(key: Key) {
        safeInitThreadSafety(key)
        safeInitWriteRequestQueue(key)
    }
}
