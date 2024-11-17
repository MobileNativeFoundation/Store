package org.mobilenativefoundation.store.store5

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.util.FakeFetcher
import kotlin.test.Test
import kotlin.test.assertEquals

@FlowPreview
@ExperimentalCoroutinesApi
class StreamWithoutSourceOfTruthTests {
    private val testScope = TestScope()

    @Test
    fun streamWithoutPersisterAndCacheEnabled() =
        testScope.runTest {
            val fetcher =
                FakeFetcher(
                    3 to "three-1",
                    3 to "three-2",
                )
            val pipeline =
                StoreBuilder.from(fetcher)
                    .scope(testScope)
                    .build()
            val twoItemsNoRefresh =
                async {
                    pipeline.stream(
                        StoreReadRequest.cached(3, refresh = false),
                    ).take(3).toList()
                }
            delay(1_000) // make sure the async block starts first
            pipeline.stream(StoreReadRequest.fresh(3)).test {
                assertEquals(
                    StoreReadResponse.Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem()
                )

                assertEquals(
                    StoreReadResponse.Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem()
                )
            }

            assertEquals(
                listOf(
                    StoreReadResponse.Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    StoreReadResponse.Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    StoreReadResponse.Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                ),
                twoItemsNoRefresh.await(),
            )
        }

    @Test
    fun streamWithoutPersisterAndCacheDisabled() =
        testScope.runTest {
            val fetcher =
                FakeFetcher(
                    3 to "three-1",
                    3 to "three-2",
                )
            val pipeline =
                StoreBuilder.from(fetcher)
                    .scope(testScope)
                    .disableCache()
                    .build()
            val twoItemsNoRefresh =
                async {
                    pipeline.stream(
                        StoreReadRequest.cached(3, refresh = false),
                    ).take(3).toList()
                }
            delay(1_000) // make sure the async block starts first
            pipeline.stream(StoreReadRequest.fresh(3)).test {
                assertEquals(
                    StoreReadResponse.Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem()
                )

                assertEquals(
                    StoreReadResponse.Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem()
                )
            }

            assertEquals(
                listOf(
                    StoreReadResponse.Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    StoreReadResponse.Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    StoreReadResponse.Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                ),
                twoItemsNoRefresh.await(),
            )
        }
}
