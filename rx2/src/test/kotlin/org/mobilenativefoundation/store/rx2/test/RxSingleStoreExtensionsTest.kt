package org.mobilenativefoundation.store.rx2.test

import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.rx2.freshSingle
import org.mobilenativefoundation.store.rx2.getSingle
import org.mobilenativefoundation.store.rx2.ofMaybe
import org.mobilenativefoundation.store.rx2.ofResultSingle
import org.mobilenativefoundation.store.rx2.withScheduler
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.FetcherResult
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StoreBuilder
import java.util.concurrent.atomic.AtomicInteger

@ExperimentalStoreApi
@RunWith(JUnit4::class)
@FlowPreview
@ExperimentalCoroutinesApi
class RxSingleStoreExtensionsTest {
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
                    writer = { key, value ->
                        Completable.fromAction { fakeDisk[key] = value }
                    },
                    delete = { key ->
                        Completable.fromAction { fakeDisk.remove(key) }
                    },
                    deleteAll = {
                        Completable.fromAction { fakeDisk.clear() }
                    },
                ),
        )
            .withScheduler(Schedulers.trampoline())
            .build()

    @Test
    fun `store rx extension tests`() {
        // Return from cache - after initial fetch
        store.getSingle(3)
            .test()
            .await()
            .assertValue("3 1")

        // Return from cache
        store.getSingle(3)
            .test()
            .await()
            .assertValue("3 1")

        // Return from fresh - forcing a new fetch
        store.freshSingle(3)
            .test()
            .await()
            .assertValue("3 2")

        // Return from cache - different to initial
        store.getSingle(3)
            .test()
            .await()
            .assertValue("3 2")
    }
}
