package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult

/** Adapts the public success-or-throw lambda sugar to the regular [Fetcher] interface. */
@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
internal class LambdaFetcher<K : StoreKey, V : Any>(
    private val fetch: suspend (K) -> V,
) : Fetcher<K, V> {
    override suspend fun fetch(
        key: K,
        etag: String?,
    ): FetcherResult<V> = FetcherResult.Success(fetch(key))
}

/** Adapts the public rich-result lambda sugar to the regular [Fetcher] interface. */
@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
internal class ResultFetcher<K : StoreKey, V : Any>(
    private val fetch: suspend (K) -> FetcherResult<V>,
) : Fetcher<K, V> {
    override suspend fun fetch(
        key: K,
        etag: String?,
    ): FetcherResult<V> = fetch(key)
}
