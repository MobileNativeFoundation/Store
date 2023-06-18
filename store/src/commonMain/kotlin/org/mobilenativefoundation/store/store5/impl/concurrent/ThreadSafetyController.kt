package org.mobilenativefoundation.store.store5.impl.concurrent

import org.mobilenativefoundation.store.store5.impl.RealMutableStore
import org.mobilenativefoundation.store.store5.impl.RealStore

/**
 * This internal interface defines a controller of thread-safety operations for [RealMutableStore].
 * The methods provided by this interface should ensure operations are thread-safe.
 * @param Key the type of key used in [RealStore].
 */
internal interface ThreadSafetyController<Key : Any> {

    /**
     * Executes a provided block of code ensuring that the operation is thread-safe.
     * This method should ensure that only one operation can be executed at a time for a given key.
     *
     * @param key The key associated with the operation.
     * @param block The block of code to be executed in a thread-safe manner. The receiver of the block is a `ThreadSafety` object.
     * @return The output of the block of code which can be of any type.
     */
    suspend fun <Output : Any?> withThreadSafety(
        key: Key,
        block: suspend ThreadSafety.() -> Output
    ): Output

    /**
     * Safely initializes thread safety mechanisms for a given key.
     * If the thread safety mechanisms are already initialized for the key, this operation should be ignored.
     *
     * @param key The key associated with the operation.
     */
    suspend fun safeInit(key: Key)
}
