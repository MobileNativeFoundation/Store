package org.mobilenativefoundation.store.store5.impl.concurrent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RealThreadSafetyController<Key : Any, Output : Any> : ThreadSafetyController<Key> {

    private val storeLock = Mutex()
    private val keyToThreadSafety = mutableMapOf<Key, ThreadSafety>()

    override suspend fun <Output> withThreadSafety(
        key: Key,
        block: suspend ThreadSafety.() -> Output
    ): Output {
        storeLock.lock()
        val threadSafety = requireNotNull(keyToThreadSafety[key])
        val output = threadSafety.block()
        storeLock.unlock()
        return output
    }

    override suspend fun safeInit(key: Key) = storeLock.withLock {
        if (keyToThreadSafety[key] == null) {
            keyToThreadSafety[key] = ThreadSafety()
        }
    }
}
