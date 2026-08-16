package org.mobilenativefoundation.store6.paging

import androidx.paging.InvalidatingPagingSourceFactory
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey

/**
 * Builds an androidx [InvalidatingPagingSourceFactory] over this store.
 *
 * Each load consumes at most one terminal outcome from [Store.stream]. After a page is ready, its
 * generation keeps that same stream collection active. Any later data frame, including equal or
 * stale data, and any absent-value loading transition invalidates the paging source. Revalidation
 * and error frames do not. Loads never call `Store.get`, so values projected by a configured stream
 * overlay remain visible. Calling `invalidate()` on the returned factory invalidates every paging
 * source previously created by that factory. When the pager is no longer used, call `invalidate()`
 * on the returned factory or close the store to release the generation's active Store stream
 * collectors.
 *
 * @throws IllegalStateException if [configure] omits a required builder door
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any, PK : Any, Item : Any> Store<K, V>.pagingSourceFactory(
    configure: StorePagingBuilder<K, V, PK, Item>.() -> Unit,
): InvalidatingPagingSourceFactory<PK, Item> {
    val config = StorePagingBuilder<K, V, PK, Item>().apply(configure).build()
    return InvalidatingPagingSourceFactory {
        lateinit var pagingSource: StorePagingSource<K, V, PK, Item>
        val watcher = GenerationWatcher(this) { pagingSource.invalidate() }
        pagingSource = StorePagingSource(this, config, watcher)
        pagingSource.registerInvalidatedCallback(watcher::cancel)
        pagingSource
    }
}
