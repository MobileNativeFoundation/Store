package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import org.mobilenativefoundation.store.store5.Clear
import org.mobilenativefoundation.store.store5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import org.mobilenativefoundation.store.store5.StoreWriteResponse
import org.mobilenativefoundation.store.store5.impl.concurrent.ThreadSafetyController

internal class RealMutableStore<Key : Any, Network : Any, Output : Any, Local : Any>(
    private val delegate: RealStore<Key, Network, Output, Local>,
    private val writeRequestHandler: WriteRequestHandler<Key, Output>,
    private val conflictResolver: ConflictResolver<Key>,
    private val threadSafetyController: ThreadSafetyController<Key>,
    private val writeRequestStackHandler: WriteRequestStackHandler<Key, Output>
) : MutableStore<Key, Output>, Clear.Key<Key> by delegate, Clear.All by delegate {

    override fun stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<Output>> =
        flow {
            safeInitStore(request.key)
            conflictResolver.eagerlyResolveConflicts(request.key)
            delegate.stream(request).collect { emit(it) }
        }

    @ExperimentalStoreApi
    override fun <Response : Any> stream(requestStream: Flow<StoreWriteRequest<Key, Output, Response>>): Flow<StoreWriteResponse> =
        flow {
            requestStream
                .onEach {
                    safeInitStore(it.key)
                    writeRequestStackHandler.add(it)
                }
                .collect {
                    emit(writeRequestHandler(it))
                }
        }

    @ExperimentalStoreApi
    override suspend fun <Response : Any> write(request: StoreWriteRequest<Key, Output, Response>): StoreWriteResponse =
        stream(flowOf(request)).first()

    private suspend fun safeInitStore(key: Key) {
        threadSafetyController.safeInit(key)
        writeRequestStackHandler.safeInit(key)
    }
}
