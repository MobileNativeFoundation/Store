package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.util.FakeFetcher
import org.mobilenativefoundation.store.store5.util.assertEmitsExactly
import kotlin.test.Test
import kotlin.test.assertEquals

@FlowPreview
@ExperimentalCoroutinesApi
class StreamWithoutSourceOfTruthTests {
    private val testScope = TestScope()

    @Test
    fun streamWithoutPersisterAndCacheEnabled() = testScope.runTest {
        val fetcher = FakeFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val pipeline = StoreBuilder.from(fetcher)
            .scope(testScope)
            .build()
        val twoItemsNoRefresh = async {
            pipeline.stream(
                StoreRequest.cached(3, refresh = false)
            ).take(3).toList()
        }
        delay(1_000) // make sure the async block starts first
        assertEmitsExactly(
            pipeline.stream(StoreRequest.fresh(3)),
            listOf(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                    value = "three-2",
                    origin = ResponseOrigin.Fetcher
                )
            )
        )

        assertEquals(
            listOf(
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
            ),
            twoItemsNoRefresh.await()
        )
    }

    @Test
    fun streamWithoutPersisterAndCacheDisabled() = testScope.runTest {
        val fetcher = FakeFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val pipeline = StoreBuilder.from(fetcher)
            .scope(testScope)
            .disableCache()
            .build()
        val twoItemsNoRefresh = async {
            pipeline.stream(
                StoreRequest.cached(3, refresh = false)
            ).take(3).toList()
        }
        delay(1_000) // make sure the async block starts first
        assertEmitsExactly(
            pipeline.stream(StoreRequest.fresh(3)),
            listOf(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                    value = "three-2",
                    origin = ResponseOrigin.Fetcher
                )
            )
        )

        assertEquals(
            listOf(
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
            ),
            twoItemsNoRefresh.await()
        )
    }
}
