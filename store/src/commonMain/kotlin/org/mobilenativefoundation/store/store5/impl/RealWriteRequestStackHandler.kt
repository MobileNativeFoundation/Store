package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.OnUpdaterCompletion
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import org.mobilenativefoundation.store.store5.StoreWriteResponse
import org.mobilenativefoundation.store.store5.UpdaterResult
import org.mobilenativefoundation.store.store5.impl.concurrent.ThreadSafetyController
import org.mobilenativefoundation.store.store5.impl.definition.WriteRequestStack

@Suppress("UNCHECKED_CAST")
/**
 * Handles write request stack operations for [RealMutableStore].
 * @property threadSafetyController Instance of [ThreadSafetyController] for concurrency control.
 */
internal class RealWriteRequestStackHandler<Key : Any, Output : Any>(
    private val threadSafetyController: ThreadSafetyController<Key>
) : WriteRequestStackHandler<Key, Output> {
    /**
     * A mutable map representing a stack of write requests for each key.
     */
    private val keyToWriteRequestStack = mutableMapOf<Key, WriteRequestStack<Key, Output, *>>()

    /**
     * Checks if the write request stack for a given key is empty or not.
     */
    override fun isEmpty(key: Key): Boolean {
        return keyToWriteRequestStack[key].isNullOrEmpty()
    }

    /**
     * Adds a new write request to the stack for a given key.
     */
    override suspend fun <Response : Any> add(writeRequest: StoreWriteRequest<Key, Output, Response>) =
        withWriteRequestStackLock(writeRequest.key) {
            val result = add(writeRequest)
            result
        }

    /**
     * Initializes the write request stack for the given key if it's not already initialized.
     */
    override suspend fun safeInit(key: Key) = threadSafetyController.withThreadSafety(key) {
        if (keyToWriteRequestStack[key] == null) {
            keyToWriteRequestStack[key] = ArrayDeque()
        }
    }

    /**
     * Executes a block of code with the write request stack lock.
     * Ensures the operation is thread-safe and only one operation can happen at a time.
     */
    private suspend fun <Result : Any> withWriteRequestStackLock(
        key: Key,
        block: suspend WriteRequestStack<Key, Output, *>.() -> Result
    ): Result {
        return threadSafetyController.withThreadSafety(key) {
            writeRequests.lightswitch.lock(writeRequests.mutex)
            val output1 = keyToWriteRequestStack[key]
            val output = output1?.block()

            writeRequests.lightswitch.unlock(writeRequests.mutex)
            output ?: throw Exception()
        }
    }

    /**
     * Updates the write request stack for the given key.
     * If there are any completed requests, it will call their success callbacks.
     * If there are any outstanding requests, they will be kept on the stack.
     */
    override suspend fun <Response : Any> update(
        key: Key,
        created: Long,
        updaterResult: UpdaterResult.Success,
        onUpdaterCompletion: OnUpdaterCompletion<Response>?
    ) {
        val nextWriteRequestStack =
            processWriteRequests(key, created, updaterResult, onUpdaterCompletion)

        threadSafetyController.withThreadSafety(key) {
            keyToWriteRequestStack[key] = nextWriteRequestStack
        }
    }

    /**
     * Processes the write requests for a key and returns a deque of outstanding requests.
     */
    private suspend fun <Response : Any> processWriteRequests(
        key: Key,
        created: Long,
        updaterResult: UpdaterResult.Success,
        onUpdaterCompletion: OnUpdaterCompletion<Response>?
    ) = withWriteRequestStackLock(key) {
        val outstandingWriteRequests = ArrayDeque<StoreWriteRequest<Key, Output, *>>()

        for (writeRequest in this) {
            if (writeRequest.created <= created) {
                processCompletedWriteRequest(updaterResult, onUpdaterCompletion, writeRequest)
            } else {
                outstandingWriteRequests.add(writeRequest)
            }
        }
        outstandingWriteRequests
    }

    /**
     * Processes a completed write request.
     */
    private fun <Response : Any> processCompletedWriteRequest(
        updaterResult: UpdaterResult.Success,
        onUpdaterCompletion: OnUpdaterCompletion<Response>?,
        writeRequest: StoreWriteRequest<Key, Output, *>
    ) {
        onUpdaterCompletion?.onSuccess?.invoke(updaterResult)

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
    }

    /**
     * Retrieves the latest write request from the stack for a given key.
     * Ensures thread-safety by locking the mutex associated with the write requests
     * before fetching the latest request and unlocking it after the operation is complete.
     * @param key The key for which the latest write request is to be fetched.
     * @return The latest [StoreWriteRequest] associated with the given key.
     * @throws IllegalArgumentException if there are no write requests associated with the given key.
     */
    override suspend fun <Response : Any> getLatest(key: Key): StoreWriteRequest<Key, Output, Response> =
        threadSafetyController.withThreadSafety(key) {
            writeRequests.mutex.lock()
            val writeRequest =
                keyToWriteRequestStack[key]?.lastOrNull() as? StoreWriteRequest<Key, Output, Response>
                    ?: throw Exception("There is no stack for key '$key'")
            writeRequests.mutex.unlock()
            writeRequest
        }
}
