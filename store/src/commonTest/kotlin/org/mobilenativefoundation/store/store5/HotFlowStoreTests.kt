package org.mobilenativefoundation.store.store5

import app.cash.turbine.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@ExperimentalCoroutinesApi
@FlowPreview
class HotFlowStoreTests {
  private val testScope = TestScope()

  @Test
  fun givenAHotFetcherWhenTwoCachedAndOneFreshCallThenFetcherIsOnlyCalledTwice() =
    testScope.runTest {
      val fetcher = FakeFlowFetcher(3 to "three-1", 3 to "three-2")
      val pipeline = StoreBuilder.from(fetcher).scope(testScope).build()

      val job = launch {
        pipeline.stream(StoreReadRequest.cached(3, refresh = false)).test {
          assertEquals(
            StoreReadResponse.Loading(origin = StoreReadResponseOrigin.Fetcher()),
            awaitItem(),
          )

          assertEquals(
            StoreReadResponse.Data(value = "three-1", origin = StoreReadResponseOrigin.Fetcher()),
            awaitItem(),
          )
        }

        pipeline.stream(StoreReadRequest.cached(3, refresh = false)).test {
          assertEquals(
            StoreReadResponse.Data(value = "three-1", origin = StoreReadResponseOrigin.Cache),
            awaitItem(),
          )
        }

        pipeline.stream(StoreReadRequest.fresh(3)).test {
          assertEquals(
            StoreReadResponse.Loading(origin = StoreReadResponseOrigin.Fetcher()),
            awaitItem(),
          )

          assertEquals(
            StoreReadResponse.Data(value = "three-2", origin = StoreReadResponseOrigin.Fetcher()),
            awaitItem(),
          )
        }
      }

      job.cancel()
    }
}

private class FakeFlowFetcher<Key : Any, Output : Any>(vararg val responses: Pair<Key, Output>) :
  Fetcher<Key, Output> {
  private var index = 0
  override val name: String? = null

  override val fallback: Fetcher<Key, Output>? = null

  override fun invoke(key: Key): Flow<FetcherResult<Output>> {
    if (index >= responses.size) {
      throw AssertionError("unexpected fetch request")
    }
    val pair = responses[index++]
    assertEquals(key, pair.first)
    return flowOf(FetcherResult.Data(pair.second))
  }
}
