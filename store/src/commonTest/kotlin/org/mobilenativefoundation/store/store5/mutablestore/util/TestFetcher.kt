package org.mobilenativefoundation.store.store5.mutablestore.util

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.FetcherResult

class TestFetcher<Key : Any, Network : Any>(
  override val name: String? = null,
  override val fallback: Fetcher<Key, Network>? = null,
) : Fetcher<Key, Network> {
  private val faked = HashMap<Key, Flow<FetcherResult<Network>>>()

  fun whenever(key: Key, block: () -> Flow<FetcherResult<Network>>) {
    faked[key] = block()
  }

  override operator fun invoke(key: Key): Flow<FetcherResult<Network>> {
    return requireNotNull(faked[key]) { "No fetcher result provided for key=$key" }
  }
}
