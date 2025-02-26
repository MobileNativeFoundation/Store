package org.mobilenativefoundation.store.rx2.test

import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.rx2.observe
import org.mobilenativefoundation.store.rx2.observeClear
import org.mobilenativefoundation.store.rx2.observeClearAll
import org.mobilenativefoundation.store.rx2.ofMaybe
import org.mobilenativefoundation.store.rx2.ofResultSingle
import org.mobilenativefoundation.store.rx2.withScheduler
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.FetcherResult
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin

@ExperimentalStoreApi
@RunWith(JUnit4::class)
@FlowPreview
@ExperimentalCoroutinesApi
class RxSingleStoreTest {
  private val atomicInteger = AtomicInteger(0)
  private var fakeDisk = mutableMapOf<Int, String>()
  private val store =
    StoreBuilder.from<Int, String, String>(
        fetcher =
          Fetcher.ofResultSingle {
            Single.fromCallable { FetcherResult.Data("$it ${atomicInteger.incrementAndGet()}") }
          },
        sourceOfTruth =
          SourceOfTruth.ofMaybe(
            reader = { Maybe.fromCallable<String> { fakeDisk[it] } },
            writer = { key, value -> Completable.fromAction { fakeDisk[key] = value } },
            delete = { key -> Completable.fromAction { fakeDisk.remove(key) } },
            deleteAll = { Completable.fromAction { fakeDisk.clear() } },
          ),
      )
      .withScheduler(Schedulers.trampoline())
      .build()

  @Test
  fun simpleTest() {
    store
      .observe(StoreReadRequest.cached(3, false))
      .test()
      .awaitCount(2)
      .assertValues(
        StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
        StoreReadResponse.Data("3 1", StoreReadResponseOrigin.Fetcher()),
      )

    store
      .observe(StoreReadRequest.cached(3, false))
      .test()
      .awaitCount(2)
      .assertValues(
        StoreReadResponse.Data("3 1", StoreReadResponseOrigin.Cache),
        StoreReadResponse.Data("3 1", StoreReadResponseOrigin.SourceOfTruth),
      )

    store
      .observe(StoreReadRequest.fresh(3))
      .test()
      .awaitCount(2)
      .assertValues(
        StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
        StoreReadResponse.Data("3 2", StoreReadResponseOrigin.Fetcher()),
      )

    store
      .observe(StoreReadRequest.cached(3, false))
      .test()
      .awaitCount(2)
      .assertValues(
        StoreReadResponse.Data("3 2", StoreReadResponseOrigin.Cache),
        StoreReadResponse.Data("3 2", StoreReadResponseOrigin.SourceOfTruth),
      )
  }

  @Test
  fun `GIVEN a store with persister values WHEN observeClear is Called THEN next Store get hits network`() {
    fakeDisk[3] = "seeded occurrence"

    store.observeClear(3).blockingGet()

    store
      .observe(StoreReadRequest.cached(3, false))
      .test()
      .awaitCount(2)
      .assertValues(
        StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
        StoreReadResponse.Data("3 1", StoreReadResponseOrigin.Fetcher()),
      )
  }

  @Test
  fun `GIVEN a store with persister values WHEN observeClearAll is called THEN next Store get calls both hit network`() {
    fakeDisk[3] = "seeded occurrence"
    fakeDisk[4] = "another seeded occurrence"

    store.observeClearAll().blockingGet()

    store
      .observe(StoreReadRequest.cached(3, false))
      .test()
      .awaitCount(2)
      .assertValues(
        StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
        StoreReadResponse.Data("3 1", StoreReadResponseOrigin.Fetcher()),
      )

    store
      .observe(StoreReadRequest.cached(4, false))
      .test()
      .awaitCount(2)
      .assertValues(
        StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
        StoreReadResponse.Data("4 2", StoreReadResponseOrigin.Fetcher()),
      )
  }
}
