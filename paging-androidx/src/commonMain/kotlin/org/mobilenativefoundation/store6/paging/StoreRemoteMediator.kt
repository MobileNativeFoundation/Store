package org.mobilenativefoundation.store6.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey

/**
 * Drives store freshness from Paging boundary signals.
 *
 * Refresh loads invalidate the initial page key before reading it. Append and prepend loads read
 * the boundary page under [Freshness.CachedOrFetch]. A `null` directional key ends pagination
 * without reading the store. Refresh invalidates only the mapped initial page key. Call
 * [Store.invalidateNamespace] before triggering a Paging refresh when the whole query must be
 * invalidated. Typed [StoreException] failures are returned as [MediatorResult.Error].
 */
@ExperimentalStoreApi
@ExperimentalPagingApi
public abstract class StoreRemoteMediator<K : StoreKey, V : Any, PK : Any, Item : Any>(
    private val store: Store<K, V>,
) : RemoteMediator<PK, Item>() {
    /**
     * Maps a pagination key and Paging load size to the store key for that page. A `null`
     * pagination key identifies the initial refresh. Refresh receives
     * `PagingConfig.initialLoadSize`; append and prepend receive `PagingConfig.pageSize`.
     */
    public abstract fun pageKey(
        paginationKey: PK?,
        loadSize: Int,
    ): K

    /** Extracts the next pagination key, or `null` at the terminal edge. */
    public abstract fun nextKey(
        key: K,
        value: V,
    ): PK?

    /** Extracts the previous pagination key. The default returns `null` for forward-only paging. */
    public open fun prevKey(
        key: K,
        value: V,
    ): PK? = null

    /** Selects freshness for refresh loads. The default is [Freshness.MustBeFresh]. */
    public open fun refreshFreshness(): Freshness = Freshness.MustBeFresh

    final override suspend fun load(
        loadType: LoadType,
        state: PagingState<PK, Item>,
    ): MediatorResult {
        return try {
            when (loadType) {
                LoadType.REFRESH -> {
                    val key = pageKey(
                        paginationKey = null,
                        loadSize = state.config.initialLoadSize,
                    )
                    store.invalidate(key)
                    val value = store.get(key, refreshFreshness())
                    MediatorResult.Success(endOfPaginationReached = nextKey(key, value) == null)
                }

                LoadType.APPEND -> {
                    val paginationKey =
                        state.pages.lastOrNull()?.nextKey
                            ?: return MediatorResult.Success(endOfPaginationReached = true)
                    val key = pageKey(paginationKey, state.config.pageSize)
                    val value = store.get(key, Freshness.CachedOrFetch)
                    MediatorResult.Success(endOfPaginationReached = nextKey(key, value) == null)
                }

                LoadType.PREPEND -> {
                    val paginationKey =
                        state.pages.firstOrNull()?.prevKey
                            ?: return MediatorResult.Success(endOfPaginationReached = true)
                    val key = pageKey(paginationKey, state.config.pageSize)
                    val value = store.get(key, Freshness.CachedOrFetch)
                    MediatorResult.Success(endOfPaginationReached = prevKey(key, value) == null)
                }
            }
        } catch (exception: StoreException) {
            MediatorResult.Error(exception)
        }
    }
}
