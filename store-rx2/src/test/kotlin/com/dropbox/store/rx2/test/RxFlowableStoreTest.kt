package com.dropbox.store.rx2.test

import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.store.rx2.observe
import com.dropbox.store.rx2.fromFlowable
import com.dropbox.store.rx2.withFlowablePersister
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Single
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicInteger

@RunWith(JUnit4::class)
class RxFlowableStoreTest {
    val atomicInteger = AtomicInteger(0)
    val fakeDisk = mutableMapOf<Int, String>()
    val store =
        StoreBuilder.fromFlowable<Int, String> {
            Flowable.create({ emitter ->
                emitter.onNext("$it ${atomicInteger.incrementAndGet()} occurrence")
                emitter.onNext("$it ${atomicInteger.incrementAndGet()} occurrence")
                emitter.onComplete()
            }, BackpressureStrategy.LATEST)
        }
            .withFlowablePersister(
                reader = {
                    if (fakeDisk[it] != null)
                        Flowable.fromCallable { fakeDisk[it]!! }
                    else
                        Flowable.empty()

                },
                writer = { key, value ->
                    Single.fromCallable { fakeDisk[key] = value }
                }
            )
            .build()

    @Test
    fun simpleTest() {
        val values = store.observe(StoreRequest.fresh(3))
            .test()
            .awaitCount(3)
            .assertValues(
                StoreResponse.Loading<String>(ResponseOrigin.Fetcher),
                StoreResponse.Data("3 1 occurrence", ResponseOrigin.Fetcher),
                StoreResponse.Data("3 2 occurrence", ResponseOrigin.Fetcher)
            )

        val values2 = store.observe(StoreRequest.cached(3, false))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Data("3 2 occurrence", ResponseOrigin.Cache),
                StoreResponse.Data("3 2 occurrence", ResponseOrigin.Persister)
            )

        val values3 = store.observe(StoreRequest.fresh(3))
            .test()
            .awaitCount(3)
            .assertValues(
                StoreResponse.Loading<String>(ResponseOrigin.Fetcher),
                StoreResponse.Data("3 3 occurrence", ResponseOrigin.Fetcher),
                StoreResponse.Data("3 4 occurrence", ResponseOrigin.Fetcher)
            )

        val values4 = store.observe(StoreRequest.cached(3, false))
            .test()
            .awaitCount(2)
            .assertValues(
                StoreResponse.Data("3 4 occurrence", ResponseOrigin.Cache),
                StoreResponse.Data("3 4 occurrence", ResponseOrigin.Persister)
            )
    }
}
