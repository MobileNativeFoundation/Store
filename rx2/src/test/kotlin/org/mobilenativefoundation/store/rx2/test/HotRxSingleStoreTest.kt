package org.mobilenativefoundation.store.rx2.test

import com.google.common.truth.Truth.assertThat
import io.reactivex.Single
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mobilenativefoundation.store.rx2.ofResultSingle
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.FetcherResult
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin

@RunWith(JUnit4::class)
@FlowPreview
@ExperimentalCoroutinesApi
class HotRxSingleStoreTest {
  private val testScope = TestCoroutineScope()

  @Test
  fun `GIVEN a hot fetcher WHEN two cached and one fresh call THEN fetcher is only called twice`() =
    testScope.runBlockingTest {
      val fetcher: FakeRxFetcher<Int, FetcherResult<String>> =
        FakeRxFetcher(3 to FetcherResult.Data("three-1"), 3 to FetcherResult.Data("three-2"))
      val pipeline =
        StoreBuilder.from(Fetcher.ofResultSingle<Int, String> { fetcher.fetch(it) })
          .scope(testScope)
          .build()

      assertThat(pipeline.stream(StoreReadRequest.cached(3, refresh = false)))
        .emitsExactly(
          StoreReadResponse.Loading(origin = StoreReadResponseOrigin.Fetcher()),
          StoreReadResponse.Data(value = "three-1", origin = StoreReadResponseOrigin.Fetcher()),
        )
      assertThat(pipeline.stream(StoreReadRequest.cached(3, refresh = false)))
        .emitsExactly(
          StoreReadResponse.Data(value = "three-1", origin = StoreReadResponseOrigin.Cache)
        )

      assertThat(pipeline.stream(StoreReadRequest.fresh(3)))
        .emitsExactly(
          StoreReadResponse.Loading(origin = StoreReadResponseOrigin.Fetcher()),
          StoreReadResponse.Data(value = "three-2", origin = StoreReadResponseOrigin.Fetcher()),
        )
    }
}

class FakeRxFetcher<Key, Output>(vararg val responses: Pair<Key, Output>) {
  private var index = 0

  @Suppress("RedundantSuspendModifier") // needed for function reference
  fun fetch(key: Key): Single<Output> {
    // will throw if fetcher called more than twice
    if (index >= responses.size) {
      throw AssertionError("unexpected fetch request")
    }
    val pair = responses[index++]
    assertThat(pair.first).isEqualTo(key)
    return Single.just(pair.second)
  }
}
