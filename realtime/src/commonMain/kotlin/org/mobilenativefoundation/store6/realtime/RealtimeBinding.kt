package org.mobilenativefoundation.store6.realtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle

/**
 * Interprets [RealtimeMessage] values as Store operations.
 *
 * Construct through [realtimeBinding] (adopting) or [invalidatingRealtimeBinding]. This type owns
 * no [kotlinx.coroutines.CoroutineScope] and has nothing to close. The application owns the
 * inbound connection, reconnect, and backoff. [consume] is one scheduler-agnostic pass: it
 * applies messages in arrival order until the flow completes or a Store operation fails.
 *
 * [apply] is serialized per binding so an adopting [RealtimeMessage.Upsert] never interleaves its
 * `apply` / `confirmFresh` pair with another caller. Cancellation of that pair can leave the
 * value committed without bookkeeping success, the same non-atomic window as the mutations
 * acknowledgement path. A fetch already in flight is not cancelled; a fetch commit that runs
 * after `StoreWriteHandle.apply` is later source-of-truth authority.
 *
 * Failures from the bound Store propagate unchanged: [StoreException] for persistence and
 * bookkeeping failures, and `IllegalStateException` with the message `Store is closed.` after
 * [Store.close].
 *
 * @param K the key type accepted by the bound Store
 * @param V the non-null value type committed by an adopting [RealtimeMessage.Upsert]
 */
@ExperimentalStoreApi
public class RealtimeBinding<K : StoreKey, V : Any> internal constructor(
    private val store: Store<K, V>,
    private val handle: StoreWriteHandle<K, V>?,
) {
    private val mutex = Mutex()

    /**
     * Applies one [message] under the binding's mutex.
     *
     * @throws StoreException if the corresponding Store or write-handle operation fails
     * @throws IllegalStateException if the bound Store is already closed
     */
    public suspend fun apply(message: RealtimeMessage<K, V>) {
        mutex.withLock {
            when (message) {
                is RealtimeMessage.Upsert -> adoptOrInvalidate(message)
                is RealtimeMessage.Unchanged -> handle?.confirmFresh(message.key, message.etag)
                is RealtimeMessage.Changed -> markStale(message.key)
                is RealtimeMessage.ChangedNamespace -> store.invalidateNamespace(message.namespace)
                is RealtimeMessage.ChangedAll -> store.invalidateAll()
                is RealtimeMessage.Deleted -> store.clear(message.key)
            }
        }
    }

    /**
     * Applies [messages] sequentially until the flow completes.
     *
     * A thrown Store failure ends the pass. Messages that already applied stay applied. The
     * collector is cooperative with cancellation at each [apply] suspension point.
     *
     * @throws StoreException if a Store or write-handle operation fails
     * @throws IllegalStateException if the bound Store is already closed
     */
    public suspend fun consume(messages: Flow<RealtimeMessage<K, V>>) {
        messages.collect { message -> apply(message) }
    }

    private suspend fun adoptOrInvalidate(message: RealtimeMessage.Upsert<K, V>) {
        val writeHandle = handle
        if (writeHandle != null) {
            writeHandle.apply(message.key, message.value)
            writeHandle.confirmFresh(message.key, message.etag)
        } else {
            store.invalidate(message.key)
        }
    }

    private suspend fun markStale(key: K) {
        val writeHandle = handle
        if (writeHandle != null) {
            writeHandle.markStale(key)
        } else {
            store.invalidate(key)
        }
    }
}
