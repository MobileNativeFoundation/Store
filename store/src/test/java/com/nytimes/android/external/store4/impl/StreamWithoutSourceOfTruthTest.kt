package com.nytimes.android.external.store4.impl

import com.nytimes.android.external.store4.FlowStoreBuilder
import com.nytimes.android.external.store4.ResponseOrigin
import com.nytimes.android.external.store4.StoreRequest
import com.nytimes.android.external.store4.StoreResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@ExperimentalCoroutinesApi
@RunWith(Parameterized::class)
class StreamWithoutSourceOfTruthTest(
        private val enableCache: Boolean
) {
    private val testScope = TestCoroutineScope()

    @Test
    fun streamWithoutPersister() = testScope.runBlockingTest {
        val fetcher = FakeFetcher(
                3 to "three-1",
                3 to "three-2"
        )
        val pipeline = FlowStoreBuilder.fromNonFlow<Int, String, String>(fetcher::fetch)
                .scope(testScope)
                .let {
                    if (enableCache) {
                        it
                    } else {
                        it.disableCache()
                    }
                }.build()
        val twoItemsNoRefresh = async {
            pipeline.stream(
                    StoreRequest.cached(3, refresh = false)
            ).take(3).toList()
        }
        delay(1_000) // make sure the async block starts first
        pipeline.stream(StoreRequest.fresh(3)).assertItems(
                StoreResponse.Loading(
                        origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                        value = "three-2",
                        origin = ResponseOrigin.Fetcher
                )
        )
        println("!")
        assertThat(twoItemsNoRefresh.await()).containsExactly(
                StoreResponse.Loading(
                        origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                        value = "three-1",
                        origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                        value = "three-2",
                        origin = ResponseOrigin.Fetcher
                )
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "enableCache={0}")
        fun params() = arrayOf(true, false)
    }
}