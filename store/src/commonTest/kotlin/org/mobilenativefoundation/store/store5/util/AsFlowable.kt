package org.mobilenativefoundation.store.store5.util

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store.store5.SourceOfTruth

/**
 * Only used in FlowStoreTest. We should get rid of it eventually.
 */
class SimplePersisterAsFlowable<Key : Any, Output : Any>(
    private val reader: suspend (Key) -> Output?,
    private val writer: suspend (Key, Output) -> Unit,
    private val delete: (suspend (Key) -> Unit)? = null,
) {
    val supportsDelete: Boolean
        get() = delete != null

    private val versionTracker = KeyTracker<Key>()

    fun flowReader(key: Key): Flow<Output?> =
        flow {
            versionTracker.keyFlow(key).collect {
                emit(reader(key))
            }
        }

    suspend fun flowWriter(
        key: Key,
        value: Output,
    ) {
        writer(key, value)
        versionTracker.invalidate(key)
    }

    suspend fun flowDelete(key: Key) {
        delete?.let {
            it(key)
            versionTracker.invalidate(key)
        }
    }
}

fun <Key : Any, Output : Any> SimplePersisterAsFlowable<Key, Output>.asSourceOfTruth() =
    SourceOfTruth.of(
        reader = ::flowReader,
        writer = ::flowWriter,
        delete = ::flowDelete.takeIf { supportsDelete },
    )

/**
 * helper class which provides Flows for Keys that can be tracked.
 */
internal class KeyTracker<Key> {
    private val lock = Mutex()

    // list of open key channels
    private val channels = mutableMapOf<Key, KeyChannel>()

    // for testing
    internal fun activeKeyCount() = channels.size

    /**
     * invalidates the given key. If there are flows returned from [keyFlow] for the given [key],
     * they'll receive a new emission
     */
    suspend fun invalidate(key: Key) {
        lock.withLock {
            channels[key]
        }?.channel?.emit(Unit)
    }

    /**
     * Returns a Flow that emits once and then every time the given [key] is invalidated via
     * [invalidate]
     */
    suspend fun keyFlow(key: Key): Flow<Unit> {
        // it is important to allocate KeyChannel lazily (ony when the returned flow is collected
        // from). Otherwise, we might just create many of them that are never observed hence never
        // cleaned up
        return flow {
            val keyChannel =
                lock.withLock {
                    channels.getOrPut(key) { KeyChannel() }.also {
                        it.collectors++ // refcount
                    }
                }
            emit(Unit)
            try {
                emitAll(keyChannel.channel)
            } finally {
                withContext(NonCancellable) {
                    lock.withLock {
                        if (--keyChannel.collectors == 0) {
                            channels.remove(key)
                        }
                    }
                }
            }
        }
    }

    /**
     * A data structure to count how many active flows we have on this channel
     */
    private class KeyChannel {
        val channel: MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        var collectors: Int = 0;
    }
}

fun <Key : Any, Output : Any> InMemoryPersister<Key, Output>.asFlowable() =
    SimplePersisterAsFlowable(
        reader = this::read,
        writer = this::write,
    )
