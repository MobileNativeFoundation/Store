package com.dropbox.store.rx2.test

import com.dropbox.android.external.store4.ExperimentalStoreApi
import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.FetcherResult
import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.SourceOfTruth
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.store.rx2.observe
import com.dropbox.store.rx2.observeClear
import com.dropbox.store.rx2.observeClearAll
import com.dropbox.store.rx2.ofMaybe
import com.dropbox.store.rx2.ofResultSingle
import com.dropbox.store.rx2.withScheduler
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicInteger

@ExperimentalStoreApi
@RunWith(JUnit4::class)
class RxSingleStoreTest {
    private val atomicInteger = AtomicInteger(0)
    private var fakeDisk = mutableMapOf<Int, String>()
    private val store =
        StoreBuilder.from<Int, String, String>(
            fetcher = Fetcher.ofResultSingle {
                Single.fromCallable { FetcherResult.Data("$it ${atomicInteger.incrementAndGet()}") }
            },
            sourceOfTruth = SourceOfTruth.ofMaybe(
                reader = { Maybe.fromCallable<String> { fakeDisk[it] } },
                writer = { key, value ->
                    Completable.fromAction { fakeDisk[key] = value }
                },
                delete = { key ->
                    Completable.fromAction { fakeDisk.remove(key) }
                },
                deleteAll = {
                    Completable.fromAction { fakeDisk.clear() }
                }
            )
        )
            .withScheduler(Schedulers.trampoline())
            .build()

    @Test
    fun simpleTest() {
        store.observe(StoreRequest.cached(3, false))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Loading(ResponseOrigin.Fetcher),
                StoreResponse.Data("3 1", ResponseOrigin.Fetcher)
            )

        store.observe(StoreRequest.cached(3, false))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Data("3 1", ResponseOrigin.Cache),
                StoreResponse.Data("3 1", ResponseOrigin.SourceOfTruth)
            )

        store.observe(StoreRequest.fresh(3))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Loading(ResponseOrigin.Fetcher),
                StoreResponse.Data("3 2", ResponseOrigin.Fetcher)
            )

        store.observe(StoreRequest.cached(3, false))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Data("3 2", ResponseOrigin.Cache),
                StoreResponse.Data("3 2", ResponseOrigin.SourceOfTruth)
            )
    }

    @Test
    fun `GIVEN a store with persister values WHEN observeClear is Called THEN next Store get hits network`() {
        fakeDisk[3] = "seeded occurrence"

        store.observeClear(3).blockingGet()

        store.observe(StoreRequest.cached(3, false))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Loading(ResponseOrigin.Fetcher),
                StoreResponse.Data("3 1", ResponseOrigin.Fetcher)
            )
    }

    @Test
    fun `GIVEN a store with persister values WHEN observeClearAll is called THEN next Store get calls both hit network`() {
        fakeDisk[3] = "seeded occurrence"
        fakeDisk[4] = "another seeded occurrence"

        store.observeClearAll().blockingGet()

        store.observe(StoreRequest.cached(3, false))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Loading(ResponseOrigin.Fetcher),
                StoreResponse.Data("3 1", ResponseOrigin.Fetcher)
            )

        store.observe(StoreRequest.cached(4, false))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Loading(ResponseOrigin.Fetcher),
                StoreResponse.Data("4 2", ResponseOrigin.Fetcher)
            )
    }
}
