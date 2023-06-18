package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.OnUpdaterCompletion
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import org.mobilenativefoundation.store.store5.UpdaterResult

/**
 * This internal interface defines a handler for stacks of write requests.
 * @param Key The type of key used in [RealStore].
 * @param Output The type of output used in [RealStore].
 */
internal interface WriteRequestStackHandler<Key : Any, Output : Any> {

    /**
     * Checks whether the stack of write requests associated with the given key is empty.
     * @param key Key of the write requests stack.
     * @return Boolean Whether the write requests stack is empty.
     */
    fun isEmpty(key: Key): Boolean

    /**
     * Adds a new write request to the stack associated with the given key.
     * @param writeRequest The write request to be added to the stack.
     * @return Boolean Whether the addition was successful.
     */
    suspend fun <Response : Any> add(writeRequest: StoreWriteRequest<Key, Output, Response>): Boolean

    /**
     * Initializes the stack of write requests associated with the given key.
     * If the stack is already initialized, this operation is ignored.
     * @param key Key of the write requests stack to be initialized.
     */
    suspend fun safeInit(key: Key)

    /**
     * Retrieves the latest write request from the stack associated with the given key.
     * @param key Key of the write requests stack.
     * @return The latest write request from the stack.
     * @throws NoSuchElementException if there are no write requests associated with the key.
     */
    suspend fun <Response : Any> getLatest(key: Key): StoreWriteRequest<Key, Output, Response>

    /**
     * Updates the write request stack for a given key based on the result of an updater operation.
     * @param key Key of the write requests stack to be updated.
     * @param created Timestamp of when the write request was created.
     * @param updaterResult Result of the updater operation.
     * @param onUpdaterCompletion Optional callback to be invoked upon successful completion of the updater operation.
     */
    suspend fun <Response : Any> update(
        key: Key,
        created: Long,
        updaterResult: UpdaterResult.Success,
        onUpdaterCompletion: OnUpdaterCompletion<Response>? = null
    )
}
