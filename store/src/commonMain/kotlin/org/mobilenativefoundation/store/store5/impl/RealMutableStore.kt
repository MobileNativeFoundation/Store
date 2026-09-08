package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import org.mobilenativefoundation.store.store5.internal.result.EagerConflictResolutionResult
import org.mobilenativefoundation.store.store5.internal.result.StoreDelegateWriteResult

@OptIn(ExperimentalStoreApi::class)
internal class RealMutableStore<Key : Any, Network : Any, Output : Any, Local : Any>(
    private val delegate: RealStore<Key, Network, Output, Local>,
    private val updater: Updater<Key, Output, *>,
    private val bookkeeper: Bookkeeper<Key>?,
    private val logger: Logger = DefaultLogger(),
    private val beforeAcknowledgementCommit: suspend () -> Unit = {},
) : MutableStore<Key, Output>, Clear.All {
    private val storeLock = Mutex()
    private val states = mutableMapOf<Key, MutableStoreKeyState<Key, Output>>()

    override fun <Response : Any> stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<Output>> =
        flow {
            checkMutableStoreEntry(this@RealMutableStore, request.key)
            val state = stateFor(request.key)
            // TODO(#678): Allow configuring whether a failed push should prevent a subsequent fetch.
            when (val result = tryEagerlyResolveConflicts<Response>(request.key, state)) {
                is EagerConflictResolutionResult.Error.Exception -> logger.error(result.error.toString())
                is EagerConflictResolutionResult.Error.Message -> logger.error(result.message)
                is EagerConflictResolutionResult.Success.ConflictsResolved -> logger.debug(result.value.toString())
                EagerConflictResolutionResult.Success.NoConflicts -> logger.debug("No conflicts.")
            }
            delegate.stream(request).collect { emit(it) }
        }

    @ExperimentalStoreApi
    override fun <Response : Any> stream(requestStream: Flow<StoreWriteRequest<Key, Output, Response>>): Flow<StoreWriteResponse> =
        flow {
            requestStream.collect { emit(writeRequest(it)) }
        }

    @ExperimentalStoreApi
    override suspend fun <Response : Any> write(request: StoreWriteRequest<Key, Output, Response>): StoreWriteResponse =
        stream(flowOf(request)).first()

    override suspend fun clear(key: Key) {
        checkMutableStoreEntry(this, key)
        delegate.clear(key)
    }

    @ExperimentalStoreApi
    override suspend fun clear() {
        checkMutableStoreEntry(this)
        delegate.clear()
    }

    private suspend fun writeRequest(request: StoreWriteRequest<Key, Output, *>): StoreWriteResponse =
        try {
            checkMutableStoreEntry(this, request.key)
            val state = stateFor(request.key)
            val entry = PendingStoreWrite(request)
            val localResult =
                state.localMutex.withLock {
                    withMutableStoreAdapter(this, request.key) {
                        delegate.write(request.key, request.value).also { result ->
                            // Admit inside the adapter context before returning through prompt cancellation.
                            if (result is StoreDelegateWriteResult.Success) state.pending.add(entry)
                        }
                    }
                }
            when (localResult) {
                is StoreDelegateWriteResult.Error.Exception -> {
                    if (localResult.error is CancellationException) throw localResult.error
                    StoreWriteResponse.Error.Exception(localResult.error)
                }
                is StoreDelegateWriteResult.Error.Message -> StoreWriteResponse.Error.Message(localResult.error)
                is StoreDelegateWriteResult.Success -> requireNotNull(synchronize(request.key, state, entry)).toWriteResponse()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            StoreWriteResponse.Error.Exception(error)
        }

    /**
     * A writer releases localMutex before waiting here. The only nested per-key lock order is
     * remoteMutex then localMutex; local persistence can therefore proceed while the updater waits.
     */
    private suspend fun synchronize(
        key: Key,
        state: MutableStoreKeyState<Key, Output>,
        entry: PendingStoreWrite<Key, Output>? = null,
    ): UpdaterResult? {
        if (entry == null && bookkeeper == null) return null
        var completed = emptyList<PendingStoreWrite<Key, Output>>()
        var completedResult: UpdaterResult.Success? = null
        try {
            return state.remoteMutex.withLock remote@{
                val acknowledged = state.localMutex.withLock { entry?.acknowledged }
                if (acknowledged != null) return@remote acknowledged

                val snapshot =
                    state.localMutex.withLock {
                        if (entry != null) {
                            MutableStoreSyncSnapshot(state.pending.last().request.value, state.pending.toList())
                        } else {
                            withMutableStoreAdapter(this, key) {
                                val failed = bookkeeper?.getLastFailedSync(key)
                                if (failed == null && state.pending.isEmpty()) return@withMutableStoreAdapter null
                                // Direct SourceOfTruth updates may be newer than the last pending request.
                                val latest = delegate.latestOrNull(key) ?: return@withMutableStoreAdapter null
                                MutableStoreSyncSnapshot(latest, state.pending.toList())
                            }
                        }
                    } ?: return@remote null

                val result = post(key, snapshot.value)
                when (result) {
                    is UpdaterResult.Success -> {
                        beforeAcknowledgementCommit()
                        state.localMutex.withLock {
                            completed = acknowledgeSnapshot(state, snapshot, result)
                            completedResult = result
                            // Keep the empty check and clear together so a new admission cannot slip in.
                            if (state.pending.isEmpty()) {
                                tryBookkeeping("clear", key) { bookkeeper?.clear(key) }
                            }
                        }
                    }
                    is UpdaterResult.Error -> {
                        state.localMutex.withLock {
                            tryBookkeeping("setLastFailedSync", key) {
                                if (bookkeeper?.setLastFailedSync(key) == false) {
                                    logger.error("Bookkeeper.setLastFailedSync returned false for key=$key.")
                                }
                            }
                        }
                    }
                }
                result
            }
        } finally {
            // The committed batch survives a canceled clear. Callbacks run after both locks release.
            completedResult?.let { deliverCallbacks(completed, it) }
        }
    }

    private suspend fun post(
        key: Key,
        value: Output,
    ): UpdaterResult =
        try {
            withMutableStoreAdapter(this, key) { updater.post(key, value) }.also { result ->
                if (result is UpdaterResult.Error.Exception && result.error is CancellationException) throw result.error
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            UpdaterResult.Error.Exception(error)
        }

    private suspend fun tryBookkeeping(
        operation: String,
        key: Key,
        block: suspend () -> Unit,
    ) {
        try {
            withMutableStoreAdapter(this, key) { block() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            logger.error("Bookkeeper.$operation failed for key=$key.", error)
        }
    }

    private suspend fun <Response : Any> tryEagerlyResolveConflicts(
        key: Key,
        state: MutableStoreKeyState<Key, Output>,
    ): EagerConflictResolutionResult<Response> =
        try {
            when (val result = synchronize(key, state)) {
                null -> EagerConflictResolutionResult.Success.NoConflicts
                is UpdaterResult.Error.Exception -> EagerConflictResolutionResult.Error.Exception(result.error)
                is UpdaterResult.Error.Message -> EagerConflictResolutionResult.Error.Message(result.message)
                is UpdaterResult.Success -> EagerConflictResolutionResult.Success.ConflictsResolved(result)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            EagerConflictResolutionResult.Error.Exception(error)
        }

    private fun deliverCallbacks(
        entries: List<PendingStoreWrite<Key, Output>>,
        result: UpdaterResult.Success,
    ) {
        var cancellation: CancellationException? = null
        fun invokeCallback(callback: () -> Unit) {
            try {
                callback()
            } catch (error: CancellationException) {
                if (cancellation == null) cancellation = error
            } catch (error: Throwable) {
                logger.error("MutableStore success callback failed.", error)
            }
        }
        val response = result.toSuccessResponse()
        for (entry in entries) {
            updater.onCompletion?.onSuccess?.let { callback -> invokeCallback { callback(result) } }
            entry.request.onCompletions?.forEach { callback -> invokeCallback { callback.onSuccess(response) } }
        }
        cancellation?.let { throw it }
    }

    private fun UpdaterResult.toWriteResponse(): StoreWriteResponse =
        when (this) {
            is UpdaterResult.Error.Exception -> StoreWriteResponse.Error.Exception(error)
            is UpdaterResult.Error.Message -> StoreWriteResponse.Error.Message(message)
            is UpdaterResult.Success -> toSuccessResponse()
        }

    private fun UpdaterResult.Success.toSuccessResponse(): StoreWriteResponse.Success =
        when (this) {
            is UpdaterResult.Success.Typed<*> -> StoreWriteResponse.Success.Typed(value)
            is UpdaterResult.Success.Untyped -> StoreWriteResponse.Success.Untyped(value)
        }

    private suspend fun stateFor(key: Key): MutableStoreKeyState<Key, Output> =
        storeLock.withLock { states.getOrPut(key) { MutableStoreKeyState() } }
}
