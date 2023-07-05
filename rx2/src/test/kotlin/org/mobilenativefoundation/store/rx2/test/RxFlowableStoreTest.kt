package org.mobilenativefoundation.store.rx2.test

import io.reactivex.BackpressureStrategy
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.schedulers.TestScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mobilenativefoundation.store.rx2.observe
import org.mobilenativefoundation.store.rx2.ofFlowable
import org.mobilenativefoundation.store.rx2.ofResultFlowable
import org.mobilenativefoundation.store.rx2.withScheduler
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.FetcherResult
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin
import java.util.concurrent.atomic.AtomicInteger

@RunWith(JUnit4::class)
@FlowPreview
@ExperimentalCoroutinesApi
class RxFlowableStoreTest {
    private val testScheduler = TestScheduler()
    private val atomicInteger = AtomicInteger(0)
    private val fakeDisk = mutableMapOf<Int, String>()
    private val store = StoreBuilder.from<Int, String, String>(
        fetcher = Fetcher.ofResultFlowable<Int, String> {
            Flowable.create(
                { emitter ->
                    emitter.onNext(
                        FetcherResult.Data("$it ${atomicInteger.incrementAndGet()} occurrence")
                    )
                    emitter.onNext(
                        FetcherResult.Data("$it ${atomicInteger.incrementAndGet()} occurrence")
                    )
                    emitter.onComplete()
                },
                BackpressureStrategy.BUFFER
            )
        },
        sourceOfTruth = SourceOfTruth.ofFlowable<Int, String, String>(
            reader = {
                if (fakeDisk[it] != null)
                    Flowable.fromCallable { fakeDisk[it]!! }
                else
                    Flowable.empty<String>()
            },
            writer = { key, value ->
                Completable.fromAction { fakeDisk[key] = value }
            }
        )
    )
        .withScheduler(testScheduler)
        .build()

    @Test
    fun simpleTest() {
        val testSubscriber1 = store.observe(StoreReadRequest.fresh(3))
            .subscribeOn(testScheduler)
            .test()
        testScheduler.triggerActions()
        testSubscriber1
            .awaitCount(3)
            .assertValues(
                StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
                StoreReadResponse.Data("3 1 occurrence", StoreReadResponseOrigin.Fetcher()),
                StoreReadResponse.Data("3 2 occurrence", StoreReadResponseOrigin.Fetcher())
            )

        val testSubscriber2 = store.observe(StoreReadRequest.cached(3, false))
            .subscribeOn(testScheduler)
            .test()
        testScheduler.triggerActions()
        testSubscriber2
            .awaitCount(2)
            .assertValues(
                StoreReadResponse.Data("3 2 occurrence", StoreReadResponseOrigin.Cache),
                StoreReadResponse.Data("3 2 occurrence", StoreReadResponseOrigin.SourceOfTruth)
            )

        val testSubscriber3 = store.observe(StoreReadRequest.fresh(3))
            .subscribeOn(testScheduler)
            .test()
        testScheduler.triggerActions()
        testSubscriber3
            .awaitCount(3)
            .assertValues(
                StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
                StoreReadResponse.Data("3 3 occurrence", StoreReadResponseOrigin.Fetcher()),
                StoreReadResponse.Data("3 4 occurrence", StoreReadResponseOrigin.Fetcher())
            )

        val testSubscriber4 = store.observe(StoreReadRequest.cached(3, false))
            .subscribeOn(testScheduler)
            .test()
        testScheduler.triggerActions()
        testSubscriber4
            .awaitCount(2)
            .assertValues(
                StoreReadResponse.Data("3 4 occurrence", StoreReadResponseOrigin.Cache),
                StoreReadResponse.Data("3 4 occurrence", StoreReadResponseOrigin.SourceOfTruth)
            )
    }
}