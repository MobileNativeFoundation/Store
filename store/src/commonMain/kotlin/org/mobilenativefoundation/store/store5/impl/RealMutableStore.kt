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

internal class RealMutableStore<Key : Any, Network : Any, Common : Any, SOT : Any>(
    private val delegate: RealStore<Key, Network, Common, SOT>,
    private val updater: Updater<Key, Common, *>,
    private val bookkeeper: Bookkeeper<Key>,
) : MutableStore<Key, Common>, Clear.Key<Key> by delegate, Clear.All by delegate {

    private val storeLock = Mutex()
    private val keyToWriteRequestQueue = mutableMapOf<Key, WriteRequestQueue<Key, Common, *>>()
    private val keyToThreadSafety = mutableMapOf<Key, ThreadSafety>()

    override fun <Response : Any> stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<Common>> =
        flow {
            safeInitStore(request.key)

            when (val eagerConflictResolutionResult = tryEagerlyResolveConflicts<Response>(request.key)) {
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
    override fun <Response : Any> stream(requestStream: Flow<StoreWriteRequest<Key, Common, Response>>): Flow<StoreWriteResponse> =
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
    override suspend fun <Response : Any> write(request: StoreWriteRequest<Key, Common, Response>): StoreWriteResponse =
        stream(flowOf(request)).first()

    private suspend fun <Response : Any> tryUpdateServer(request: StoreWriteRequest<Key, Common, Response>): UpdaterResult {
        val updaterResult = postLatest<Response>(request.key)

        if (updaterResult is UpdaterResult.Success) {
            updateWriteRequestQueue<Response>(
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

    private suspend fun <Response : Any> postLatest(key: Key): UpdaterResult {
        val writer = getLatestWriteRequest(key)
        return when (val updaterResult = updater.post(key, writer.input)) {
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
    private suspend fun <Response : Any> updateWriteRequestQueue(key: Key, created: Long, updaterResult: UpdaterResult.Success) {
        val nextWriteRequestQueue = withWriteRequestQueueLock<ArrayDeque<StoreWriteRequest<Key, Common, *>>, Response>(key) {
            val outstandingWriteRequests = ArrayDeque<StoreWriteRequest<Key, Common, *>>()

            for (writeRequest in this) {
                if (writeRequest.created <= created) {
                    updater.onCompletion?.onSuccess?.invoke(updaterResult)

                    val storeWriteResponse = when (updaterResult) {
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
    private suspend fun <Output : Any, Response : Any> withWriteRequestQueueLock(
        key: Key,
        block: suspend WriteRequestQueue<Key, Common, *>.() -> Output
    ): Output =
        withThreadSafety(key) {
            writeRequests.lightswitch.lock(writeRequests.mutex)
            val writeRequestQueue = requireNotNull(keyToWriteRequestQueue[key])
            val output = writeRequestQueue.block()
            writeRequests.lightswitch.unlock(writeRequests.mutex)
            output
        }

    private suspend fun getLatestWriteRequest(key: Key): StoreWriteRequest<Key, Common, *> = withThreadSafety(key) {
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

    private suspend fun <Response : Any> addWriteRequestToQueue(writeRequest: StoreWriteRequest<Key, Common, Response>) =
        withWriteRequestQueueLock<Unit, Response>(writeRequest.key) {
            add(writeRequest)
        }

    @AnyThread
    private suspend fun <Response : Any> tryEagerlyResolveConflicts(key: Key): EagerConflictResolutionResult<Response> =
        withThreadSafety(key) {
            val latest = delegate.latestOrNull(key)
            when {
                latest == null || conflictsMightExist(key).not() -> EagerConflictResolutionResult.Success.NoConflicts
                else -> {
                    try {
                        val updaterResult = updater.post(key, latest).also { updaterResult ->
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
