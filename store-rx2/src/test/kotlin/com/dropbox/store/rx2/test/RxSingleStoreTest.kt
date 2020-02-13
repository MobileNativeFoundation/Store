package com.dropbox.store.rx2.test

import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.store.rx2.observe
import com.dropbox.store.rx2.fromSingle
import com.dropbox.store.rx2.withScheduler
import com.dropbox.store.rx2.withSinglePersister
import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicInteger

@RunWith(JUnit4::class)
@FlowPreview
@ExperimentalCoroutinesApi
class RxSingleStoreTest {
    private val atomicInteger = AtomicInteger(0)
    private val fakeDisk = mutableMapOf<Int, String>()
    private val store =
        StoreBuilder.fromSingle<Int, String> { Single.fromCallable { "$it ${atomicInteger.incrementAndGet()} occurrence" } }
            .withSinglePersister(
                reader = {
                    if (fakeDisk[it] != null)
                        Maybe.fromCallable { fakeDisk[it]!! }
                    else
                        Maybe.empty()
                },
                writer = { key, value ->
                    Single.fromCallable { fakeDisk[key] = value }
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
                StoreResponse.Data("3 1 occurrence", ResponseOrigin.Fetcher)
            )

        store.observe(StoreRequest.cached(3, false))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Data("3 1 occurrence", ResponseOrigin.Cache),
                StoreResponse.Data("3 1 occurrence", ResponseOrigin.Persister)
            )

        store.observe(StoreRequest.fresh(3))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Loading<String>(ResponseOrigin.Fetcher),
                StoreResponse.Data("3 2 occurrence", ResponseOrigin.Fetcher)
            )

        store.observe(StoreRequest.cached(3, false))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Data("3 2 occurrence", ResponseOrigin.Cache),
                StoreResponse.Data("3 2 occurrence", ResponseOrigin.Persister)
            )
    }
}
