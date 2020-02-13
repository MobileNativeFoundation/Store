package com.dropbox.store.rx2.test

import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.store.rx2.fromSingle
import com.google.common.truth.Truth.assertThat
import io.reactivex.Single
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class HotRxSingleStoreTest {
    private val testScope = TestCoroutineScope()
    @Test
    fun `GIVEN a hot fetcher WHEN two cached and one fresh call THEN fetcher is only called twice`() =
        testScope.runBlockingTest {
            val fetcher = FakeFetcher(
                3 to "three-1",
                3 to "three-2"
            )
            val pipeline = StoreBuilder.fromSingle<Int, String> { fetcher.fetch(it) }
                .scope(testScope)
                .build()

            assertThat(pipeline.stream(StoreRequest.cached(3, refresh = false)))
                .emitsExactly(
                    StoreResponse.Loading<String>(
                        origin = ResponseOrigin.Fetcher
                    ), StoreResponse.Data(
                        value = "three-1",
                        origin = ResponseOrigin.Fetcher
                    )
                )
            assertThat(
                pipeline.stream(StoreRequest.cached(3, refresh = false))
            ).emitsExactly(
                StoreResponse.Data(
                    value = "three-1",
                    origin = ResponseOrigin.Cache
                )
            )

            assertThat(pipeline.stream(StoreRequest.fresh(3)))
                .emitsExactly(
                    StoreResponse.Loading<String>(
                        origin = ResponseOrigin.Fetcher
                    ),
                    StoreResponse.Data(
                        value = "three-2",
                        origin = ResponseOrigin.Fetcher
                    )
                )
        }
}

class FakeFetcher<Key, Output>(
    vararg val responses: Pair<Key, Output>
) {
    private var index = 0
    @Suppress("RedundantSuspendModifier") // needed for function reference
    fun fetch(key: Key): Single<Output> {
        //will throw if fetcher called more than twice
        if (index >= responses.size) {
            throw AssertionError("unexpected fetch request")
        }
        val pair = responses[index++]
        assertThat(pair.first).isEqualTo(key)
        return Single.just(pair.second)
    }
}
