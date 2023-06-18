package org.mobilenativefoundation.store.store5.impl.concurrent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RealThreadSafetyController<Key : Any, Output : Any> : ThreadSafetyController<Key> {

    private val storeLock = Mutex()
    private val keyToThreadSafety = mutableMapOf<Key, ThreadSafety>()

    var counter: Int = 0 // TODO(matt-ramotar)

    override suspend fun <Output> withThreadSafety(
        key: Key,
        block: suspend ThreadSafety.() -> Output
    ): Output = try {
        val count = counter + 1
        println("LOCKING for $key $count")
        storeLock.tryLock()
        val threadSafety = requireNotNull(keyToThreadSafety[key])
        val output = threadSafety.block()
        if (storeLock.isLocked) {
            println("UNLOCKED for $key $count")
            storeLock.unlock()
        }
        counter++
        output
    } catch (error: Throwable) {
        if (storeLock.isLocked) {
            println("UNLOCKED for $key")
            storeLock.unlock()
        }
        throw error
    }

    override suspend fun safeInit(key: Key) = storeLock.withLock {
        if (keyToThreadSafety[key] == null) {
            keyToThreadSafety[key] = ThreadSafety()
        }
    }
}
