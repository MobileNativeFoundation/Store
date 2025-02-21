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
import org.mobilenativefoundation.store.store5.internal.concurrent.ThreadSafety
import org.mobilenativefoundation.store.store5.internal.definition.WriteRequestQueue
import org.mobilenativefoundation.store.store5.internal.result.EagerConflictResolutionResult

@OptIn(ExperimentalStoreApi::class)
internal class RealMutableStore<Key : Any, Network : Any, Output : Any, Local : Any>(
    private val delegate: RealStore<Key, Network, Output, Local>,
    private val updater: Updater<Key, Output, *>,
    private val bookkeeper: Bookkeeper<Key>?,
    private val logger: Logger = DefaultLogger(),
) : MutableStore<Key, Output>, Clear.Key<Key> by delegate, Clear.All by delegate {
    private val storeLock = Mutex()
    private val keyToWriteRequestQueue = mutableMapOf<Key, WriteRequestQueue<Key, Output, *>>()
    private val keyToThreadSafety = mutableMapOf<Key, ThreadSafety>()

    override fun <Response : Any> stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<Output>> =
        flow {
            // Ensure we are ready for this key.
            safeInitStore(request.key)

            // Try to eagerly resolve conflicts before pulling from network.
            when (val eagerConflictResolutionResult = tryEagerlyResolveConflicts<Response>(request.key)) {
                // TODO(#678): Many use cases will not want to pull immediately after failing to push local changes.
                // We should enable configuration of conflict resolution strategies, such as logging, retrying, canceling.

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
                    logger.debug("No conflicts.")
                }
            }

            // Now, we can just delegate to the underlying stream.
            delegate.stream(request).collect { storeReadResponse -> emit(storeReadResponse) }
        }

    @ExperimentalStoreApi
    override fun <Response : Any> stream(requestStream: Flow<StoreWriteRequest<Key, Output, Response>>): Flow<StoreWriteResponse> =
        flow {
            // Each incoming write request is enqueued.
            // Then we try to update the network and delegate.

            requestStream
                .onEach { writeRequest ->
                    // Prepare per-key data structures.
                    safeInitStore(writeRequest.key)

                    // Enqueue the new write request.
                    addWriteRequestToQueue(writeRequest)
                }
                .collect { writeRequest ->
                    val storeWriteResponse =
                        try {
                            // Always write to local first.
                            delegate.write(writeRequest.key, writeRequest.value)

                            // Try to sync to network.
                            val updaterResult = tryUpdateServer(writeRequest)

                            // Convert UpdaterResult -> StoreWriteResponse.
                            when (updaterResult) {
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
            // We successfully synced to network, can now clear out any stale writes.
            updateWriteRequestQueue<Response>(
                key = request.key,
                created = request.created,
                updaterResult = updaterResult,
            )
            bookkeeper?.clear(request.key)
        } else {
            // Could not sync, need to record a failed timestamp.
            bookkeeper?.setLastFailedSync(request.key)
        }

        return updaterResult
    }

    /**
     * Post the very latest write for [key] to the network using [updater].
     */
    private suspend fun <Response : Any> postLatest(key: Key): UpdaterResult {
        // The "latest" is the last item in the queue for this key.
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

    /**
     * Remove or keep queue items after a successful network sync.
     */
    private suspend fun <Response : Any> updateWriteRequestQueue(
        key: Key,
        created: Long,
        updaterResult: UpdaterResult.Success,
    ) {
        val nextWriteRequestQueue =
            withWriteRequestQueueLock(key) {
                val remaining = ArrayDeque<StoreWriteRequest<Key, Output, *>>()

                for (writeRequest in this) {
                    if (writeRequest.created <= created) {
                        // Mark each relevant request as succeeded.
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

                        // Notify each on-completion callback.
                        writeRequest.onCompletions?.forEach { onStoreWriteCompletion ->
                            onStoreWriteCompletion.onSuccess(storeWriteResponse)
                        }
                    } else {
                        // Keep requests that happened after created.
                        remaining.add(writeRequest)
                    }
                }
                remaining
            }

        // Update the in-memory map outside the queue's mutex.
        storeLock.withLock {
            keyToWriteRequestQueue[key] = nextWriteRequestQueue
        }
    }

    /**
     * Locks the queue for [key] and invokes [block].
     */
    private suspend fun <Result : Any> withWriteRequestQueueLock(
        key: Key,
        block: suspend WriteRequestQueue<Key, Output, *>.() -> Result,
    ): Result {
        // Acquire the ThreadSafety object for this key without holding storeLock.
        val threadSafety = getThreadSafety(key)

        // Now safely lock the queue's own mutex.
        threadSafety.writeRequests.lightswitch.lock(threadSafety.writeRequests.mutex)
        return try {
            val queue = getQueue((key))
            queue.block()
        } finally {
            threadSafety.writeRequests.lightswitch.unlock(threadSafety.writeRequests.mutex)
        }
    }

    private suspend fun getLatestWriteRequest(key: Key): StoreWriteRequest<Key, Output, *> {
        val threadSafety = getThreadSafety(key)
        threadSafety.writeRequests.mutex.lock()
        return try {
            val queue = getQueue(key)
            require(queue.isNotEmpty()) {
                "No writes found for key=$key."
            }
            queue.last()
        } finally {
            threadSafety.writeRequests.mutex.unlock()
        }
    }

    /**
     * Checks if we have un-synced writes or a recorded failed sync for [key].
     */
    private suspend fun conflictsMightExist(key: Key): Boolean {
        val failed = bookkeeper?.getLastFailedSync(key)
        return (failed != null) || !writeRequestsQueueIsEmpty(key)
    }

    private fun writeRequestsQueueIsEmpty(key: Key): Boolean = keyToWriteRequestQueue[key].isNullOrEmpty()

    private suspend fun <Response : Any> addWriteRequestToQueue(writeRequest: StoreWriteRequest<Key, Output, Response>) =
        withWriteRequestQueueLock(writeRequest.key) {
            add(writeRequest)
        }

    private suspend fun <Response : Any> tryEagerlyResolveConflicts(key: Key): EagerConflictResolutionResult<Response> {
        // Acquire the ThreadSafety object for this key without holding storeLock.
        val threadSafety = getThreadSafety(key)

        // Lock just long enough to check if conflicts exist.
        val (latestValue, conflictsExist) =
            threadSafety.readCompletions.mutex.withLock {
                val latestValue = delegate.latestOrNull(key)
                val conflictsExist = latestValue != null && bookkeeper != null && conflictsMightExist(key)
                latestValue to conflictsExist
            }

        return if (!conflictsExist || latestValue == null) {
            EagerConflictResolutionResult.Success.NoConflicts
        } else {
            try {
                val updaterResult =
                    updater.post(key, latestValue).also { updaterResult ->
                        if (updaterResult is UpdaterResult.Success) {
                            // If it succeeds, we want to remove stale requests and clear the bookkeeper.
                            updateWriteRequestQueue<Response>(key = key, created = now(), updaterResult = updaterResult)

                            bookkeeper?.clear(key)
                        }
                    }

                when (updaterResult) {
                    is UpdaterResult.Error.Exception -> {
                        EagerConflictResolutionResult.Error.Exception(updaterResult.error)
                    }

                    is UpdaterResult.Error.Message -> {
                        EagerConflictResolutionResult.Error.Message(updaterResult.message)
                    }

                    is UpdaterResult.Success -> {
                        EagerConflictResolutionResult.Success.ConflictsResolved(updaterResult)
                    }
                }
            } catch (error: Throwable) {
                EagerConflictResolutionResult.Error.Exception(error)
            }
        }
    }

    /**
     * Ensures that [keyToThreadSafety] and [keyToWriteRequestQueue] have entries for [key].
     * We only hold [storeLock] while touching these two maps, then release it immediately.
     */
    private suspend fun safeInitStore(key: Key) {
        storeLock.withLock {
            if (keyToThreadSafety[key] == null) {
                keyToThreadSafety[key] = ThreadSafety()
            }
            if (keyToWriteRequestQueue[key] == null) {
                keyToWriteRequestQueue[key] = ArrayDeque()
            }
        }
    }

    /**
     * Retrieves the [ThreadSafety] object for [key] without reinitializing it, since [safeInitStore] handles creation.
     * We do a quick [storeLock] read then release it without nesting per-key locks inside [storeLock].
     */
    private suspend fun getThreadSafety(key: Key): ThreadSafety {
        return storeLock.withLock {
            requireNotNull(keyToThreadSafety[key]) {
                "ThreadSafety not initialized for key=$key."
            }
        }
    }

    /**
     * Helper to retrieve the queue for [key] without re-initialization logic.
     */
    private suspend fun getQueue(key: Key): WriteRequestQueue<Key, Output, *> {
        return storeLock.withLock {
            requireNotNull(keyToWriteRequestQueue[key]) {
                "No write request queue found for key=$key."
            }
        }
    }
}
