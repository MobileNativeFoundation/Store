package org.mobilenativefoundation.store6.core.seam

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey

/**
 * Optional atomicity capability for a [SourceOfTruth] (TD-11). Detectable via
 * `sot is TransactionalSourceOfTruth`; the engine never assumes it and there is deliberately no
 * silent non-atomic default.
 *
 * The row-7/8 direct-write optimization requires an extension-owned coordinated decorator
 * installed as Store persistence. Before its transaction, the per-key coordinator gates and
 * conflates downstream reader notifications and the journal-retirement [Overlay.changes] signal.
 * It writes the confirmed echo and retires the journal row inside one [withTransaction] block,
 * then after commit calls `StoreWriteHandle.apply(key, echo)` and
 * `StoreWriteHandle.confirmFresh(key, etag)` while the gate remains closed. It restarts each active
 * reader collection to recapture that collection's immediate authoritative row, atomically queues
 * each latest row while reopening the gate, then emits one retirement signal. Rollback discards
 * intermediates, performs the same authoritative recapture, and emits no signal for an uncommitted
 * retirement.
 * `StoreWriteHandle.confirmFresh` alone is not an observation mechanism.
 *
 * @param K the key type used to locate a row
 * @param V the non-null row type
 */
@ExperimentalStoreApi
@SubclassOptInRequired(DelicateStoreApi::class)
public interface TransactionalSourceOfTruth<K : StoreKey, V : Any> : SourceOfTruth<K, V> {
    /** Runs [block] atomically with respect to writes made through this source. */
    public suspend fun <R> withTransaction(block: suspend () -> R): R
}
