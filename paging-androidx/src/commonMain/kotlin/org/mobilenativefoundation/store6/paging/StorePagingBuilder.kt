package org.mobilenativefoundation.store6.paging

import androidx.paging.LoadType
import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreKey

/** Configures how store keys and values map to androidx paging loads and pages. */
@ExperimentalStoreApi
public class StorePagingBuilder<K : StoreKey, V : Any, PK : Any, Item : Any> internal constructor() {
    private var pageKey: ((paginationKey: PK?, loadSize: Int) -> K)? = null
    private var items: ((V) -> List<Item>)? = null
    private var nextKey: ((K, V) -> PK?)? = null
    private var prevKey: (K, V) -> PK? = { _, _ -> null }
    private var freshness: (LoadType) -> Freshness = { Freshness.CachedOrFetch }
    private var refreshKey: (PagingState<PK, Item>) -> PK? = { state ->
        state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.let { page -> page.prevKey ?: page.nextKey }
        }
    }
    private var itemsBefore: (K, V) -> Int = { _, _ ->
        PagingSource.LoadResult.Page.COUNT_UNDEFINED
    }
    private var itemsAfter: (K, V) -> Int = { _, _ ->
        PagingSource.LoadResult.Page.COUNT_UNDEFINED
    }

    /**
     * Maps a pagination key and load size to the store key for that page.
     *
     * This door is required. A `null` pagination key identifies the initial refresh. Page
     * parameters should be included in the returned key's canonical ID.
     */
    public fun pageKey(block: (paginationKey: PK?, loadSize: Int) -> K) {
        pageKey = block
    }

    /** Extracts page items from a store value. This door is required. */
    public fun items(block: (V) -> List<Item>) {
        items = block
    }

    /**
     * Extracts the next pagination key, or `null` at the terminal edge. This door is required.
     */
    public fun nextKey(block: (K, V) -> PK?) {
        nextKey = block
    }

    /** Extracts the previous pagination key. The default returns `null` for forward-only paging. */
    public fun prevKey(block: (K, V) -> PK?) {
        prevKey = block
    }

    /**
     * Selects store freshness for each paging [LoadType]. The default is
     * [Freshness.CachedOrFetch] for every load type.
     */
    public fun freshness(block: (LoadType) -> Freshness) {
        freshness = block
    }

    /**
     * Selects the refresh key from the current paging state.
     *
     * By default, the page closest to the anchor contributes its previous key when present and
     * its next key otherwise. A state without an anchor or closest page returns `null`.
     */
    public fun refreshKey(block: (PagingState<PK, Item>) -> PK?) {
        refreshKey = block
    }

    /**
     * Computes the number of unloaded items before a loaded page. The default is
     * [PagingSource.LoadResult.Page.COUNT_UNDEFINED].
     */
    public fun itemsBefore(block: (K, V) -> Int) {
        itemsBefore = block
    }

    /**
     * Computes the number of unloaded items after a loaded page. The default is
     * [PagingSource.LoadResult.Page.COUNT_UNDEFINED].
     */
    public fun itemsAfter(block: (K, V) -> Int) {
        itemsAfter = block
    }

    internal fun build(): StorePagingConfig<K, V, PK, Item> =
        StorePagingConfig(
            pageKey = checkNotNull(pageKey) { "pageKey must be configured." },
            items = checkNotNull(items) { "items must be configured." },
            nextKey = checkNotNull(nextKey) { "nextKey must be configured." },
            prevKey = prevKey,
            freshness = freshness,
            refreshKey = refreshKey,
            itemsBefore = itemsBefore,
            itemsAfter = itemsAfter,
        )
}
