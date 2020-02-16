package com.dropbox.store.rx2.test

import com.dropbox.android.external.store4.ExperimentalStoreApi
import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.store.rx2.fromSingle
import com.dropbox.store.rx2.observe
import com.dropbox.store.rx2.observeClear
import com.dropbox.store.rx2.observeClearAll
import com.dropbox.store.rx2.withScheduler
import com.dropbox.store.rx2.withSinglePersister
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicInteger

@ExperimentalStoreApi
@RunWith(JUnit4::class)
@FlowPreview
@ExperimentalCoroutinesApi
class RxSingleStoreTest {
    private val atomicInteger = AtomicInteger(0)
    private var fakeDisk = mutableMapOf<Int, String?>()
    private val store =
        StoreBuilder.fromSingle<Int, String> { Single.fromCallable { "$it ${atomicInteger.incrementAndGet()}" } }
            .withSinglePersister(
                reader = {
                    if (fakeDisk[it] != null)
                        Maybe.fromCallable { fakeDisk[it]!! }
                    else
                        Maybe.empty()
                },
                writer = { key, value ->
                    Single.fromCallable { fakeDisk[key] = value }
                },
                delete = { key ->
                    fakeDisk[key] = null
                    Completable.complete()
                },
                deleteAll = {
                    fakeDisk.clear()
                    Completable.complete()
                }
            )
            .withScheduler(Schedulers.io())
            .build()

    @Test
    fun simpleTest() {
        store.observe(StoreRequest.cached(3, false))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Loading<String>(ResponseOrigin.Fetcher),
                StoreResponse.Data("3 1", ResponseOrigin.Fetcher)
            )

        store.observe(StoreRequest.cached(3, false))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Data("3 1", ResponseOrigin.Cache),
                StoreResponse.Data("3 1", ResponseOrigin.Persister)
            )

        store.observe(StoreRequest.fresh(3))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Loading<String>(ResponseOrigin.Fetcher),
                StoreResponse.Data("3 2", ResponseOrigin.Fetcher)
            )

        store.observe(StoreRequest.cached(3, false))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Data("3 2", ResponseOrigin.Cache),
                StoreResponse.Data("3 2", ResponseOrigin.Persister)
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
                StoreResponse.Loading<String>(ResponseOrigin.Fetcher),
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
                StoreResponse.Loading<String>(ResponseOrigin.Fetcher),
                StoreResponse.Data("3 1", ResponseOrigin.Fetcher)
            )

        store.observe(StoreRequest.cached(4, false))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Loading<String>(ResponseOrigin.Fetcher),
                StoreResponse.Data("4 2", ResponseOrigin.Fetcher)
            )
    }
}
