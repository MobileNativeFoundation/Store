package org.mobilenativefoundation.store6.paging

import androidx.paging.LoadType
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.flow.first
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.StoreResults

@OptIn(ExperimentalStoreApi::class)
internal class StorePagingSource<K : StoreKey, V : Any, PK : Any, Item : Any>(
    private val store: Store<K, V>,
    private val config: StorePagingConfig<K, V, PK, Item>,
    private val generationWatcher: GenerationWatcher<K, V>,
) : PagingSource<PK, Item>() {
    override val jumpingSupported: Boolean = false

    override fun getRefreshKey(state: PagingState<PK, Item>): PK? = config.refreshKey(state)

    override suspend fun load(params: LoadParams<PK>): LoadResult<PK, Item> {
        if (invalid) return LoadResult.Invalid()

        val loadType =
            when (params) {
                is LoadParams.Refresh -> LoadType.REFRESH
                is LoadParams.Append -> LoadType.APPEND
                is LoadParams.Prepend -> LoadType.PREPEND
            }
        val key = config.pageKey(params.key, params.loadSize)
        val baseline =
            when (val result = generationWatcher.baseline(key, config.freshness(loadType))) {
                is GenerationWatcher.BaselineResult.Frame -> result
                is GenerationWatcher.BaselineResult.Failure -> {
                    return if (invalid) {
                        LoadResult.Invalid()
                    } else {
                        LoadResult.Error(result.throwable)
                    }
                }
                GenerationWatcher.BaselineResult.Stopped -> return LoadResult.Invalid()
            }

        if (invalid) {
            generationWatcher.discard(key, baseline)
            return LoadResult.Invalid()
        }

        val loadResult =
            try {
                when (val terminal = baseline.result) {
                    is StoreResult.Data -> terminal.toPage(key)
                    is StoreResult.Error -> terminal.toLoadError()
                    is StoreResult.Revalidated -> residentAfterRevalidation(key)
                    is StoreResult.Loading ->
                        error("Loading was selected as a terminal store result.")
                }
            } catch (failure: Throwable) {
                generationWatcher.discard(key, baseline)
                throw failure
            }

        if (invalid || loadResult !is LoadResult.Page) {
            generationWatcher.discard(key, baseline)
            return if (invalid) LoadResult.Invalid() else loadResult
        }

        generationWatcher.watch(key, baseline)
        return if (invalid) LoadResult.Invalid() else loadResult
    }

    private suspend fun residentAfterRevalidation(key: K): LoadResult<PK, Item> {
        val resident =
            store.stream(key, Freshness.LocalOnly)
                .first { result ->
                    result is StoreResult.Data || result is StoreResult.Error
                }

        if (invalid) return LoadResult.Invalid()

        val loadResult =
            when (resident) {
                is StoreResult.Data -> resident.toPage(key)
                is StoreResult.Error -> resident.toLoadError()
                is StoreResult.Loading,
                is StoreResult.Revalidated,
                -> error("LocalOnly produced a non-terminal resident result.")
            }

        return if (invalid) LoadResult.Invalid() else loadResult
    }

    private fun StoreResult.Data<V>.toPage(key: K): LoadResult.Page<PK, Item> =
        LoadResult.Page(
            data = config.items(value),
            prevKey = config.prevKey(key, value),
            nextKey = config.nextKey(key, value),
            itemsBefore = config.itemsBefore(key, value),
            itemsAfter = config.itemsAfter(key, value),
        )

    private fun StoreResult.Error.toLoadError(): LoadResult.Error<PK, Item> =
        LoadResult.Error(StoreResults.exception(error, error.underlyingCause()))
}

private fun StoreError.underlyingCause(): Throwable? =
    when (this) {
        is StoreError.Fetch -> cause
        is StoreError.Persistence -> cause
        is StoreError.Conversion -> cause
        is StoreError.FreshnessUnsatisfiable,
        is StoreError.Conflict,
        is StoreError.Missing,
        -> null
    }
