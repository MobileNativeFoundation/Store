package org.mobilenativefoundation.store6.core.seam

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey

/**
 * Optional atomicity capability for a [SourceOfTruth]. Detectable via
 * `sot is TransactionalSourceOfTruth`; the engine never assumes it and there is deliberately no
 * silent non-atomic default.
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
