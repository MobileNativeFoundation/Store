package com.dropbox.store.rx2.test

import com.dropbox.android.external.store4.ExperimentalStoreApi
import com.dropbox.android.external.store4.SourceOfTruth
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.store.rx2.freshSingle
import com.dropbox.store.rx2.fromSingle
import com.dropbox.store.rx2.fromMaybe
import com.dropbox.store.rx2.getSingle
import com.dropbox.store.rx2.withScheduler
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

@ExperimentalStdlibApi
@ExperimentalStoreApi
@RunWith(JUnit4::class)
@FlowPreview
@ExperimentalCoroutinesApi
class RxSingleStoreExtensionsTest {
    private val atomicInteger = AtomicInteger(0)
    private var fakeDisk = mutableMapOf<Int, String>()
    private val store =
        StoreBuilder.fromSingle<Int, String, String>(
            fetcher = { Single.fromCallable { "$it ${atomicInteger.incrementAndGet()}" } },
                sourceOfTruth = SourceOfTruth.fromMaybe(
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
