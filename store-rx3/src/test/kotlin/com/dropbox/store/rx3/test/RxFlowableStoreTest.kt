package com.dropbox.store.rx3.test

import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.FetcherResult
import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.SourceOfTruth
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.store.rx3.observe
import com.dropbox.store.rx3.ofFlowable
import com.dropbox.store.rx3.ofResultFlowable
import com.dropbox.store.rx3.withScheduler
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.schedulers.TestScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicInteger

@RunWith(JUnit4::class)
@FlowPreview
@ExperimentalCoroutinesApi
class RxFlowableStoreTest {
    private val testScheduler = TestScheduler()
    private val atomicInteger = AtomicInteger(0)
    private val fakeDisk = mutableMapOf<Int, String>()
    private val store =
        StoreBuilder.from<Int, String, String>(
            Fetcher.ofResultFlowable {
                Flowable.create({ emitter ->
                    emitter.onNext(
                        FetcherResult.Data("$it ${atomicInteger.incrementAndGet()} occurrence")
                    )
                    emitter.onNext(
                        FetcherResult.Data("$it ${atomicInteger.incrementAndGet()} occurrence")
                    )
                    emitter.onComplete()
                }, BackpressureStrategy.BUFFER)
            },
            sourceOfTruth = SourceOfTruth.ofFlowable(
                reader = {
                    if (fakeDisk[it] != null)
                        Flowable.fromCallable { fakeDisk[it]!! }
                    else
                        Flowable.empty<String>()
                },
                writer = { key, value ->
                    Completable.fromAction { fakeDisk[key] = value }
                }
            ))
            .withScheduler(testScheduler)
            .build()

    @Test
    fun simpleTest() {
        var testSubscriber = store.observe(StoreRequest.fresh(3))
            .subscribeOn(testScheduler)
            .test()
        testScheduler.triggerActions()
        testSubscriber
            .awaitCount(3)
            .assertValues(
                StoreResponse.Loading(ResponseOrigin.Fetcher),
                StoreResponse.Data("3 1 occurrence", ResponseOrigin.Fetcher),
                StoreResponse.Data("3 2 occurrence", ResponseOrigin.Fetcher)
            )

        testSubscriber = store.observe(StoreRequest.cached(3, false))
            .subscribeOn(testScheduler)
            .test()
        testScheduler.triggerActions()
        testSubscriber
            .awaitCount(2)
            .assertValues(
                StoreResponse.Data("3 2 occurrence", ResponseOrigin.Cache),
                StoreResponse.Data("3 2 occurrence", ResponseOrigin.SourceOfTruth)
            )

        testSubscriber = store.observe(StoreRequest.fresh(3))
            .subscribeOn(testScheduler)
            .test()
        testScheduler.triggerActions()
        testSubscriber
            .awaitCount(3)
            .assertValues(
                StoreResponse.Loading(ResponseOrigin.Fetcher),
                StoreResponse.Data("3 3 occurrence", ResponseOrigin.Fetcher),
                StoreResponse.Data("3 4 occurrence", ResponseOrigin.Fetcher)
            )

        testSubscriber = store.observe(StoreRequest.cached(3, false))
            .subscribeOn(testScheduler)
            .test()
        testScheduler.triggerActions()
        testSubscriber
            .awaitCount(2)
            .assertValues(
                StoreResponse.Data("3 4 occurrence", ResponseOrigin.Cache),
                StoreResponse.Data("3 4 occurrence", ResponseOrigin.SourceOfTruth)
            )
    }
}
