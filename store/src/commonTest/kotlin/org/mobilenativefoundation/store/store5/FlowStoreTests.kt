/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mobilenativefoundation.store.store5

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.StoreReadResponse.Data
import org.mobilenativefoundation.store.store5.StoreReadResponse.Loading
import org.mobilenativefoundation.store.store5.impl.extensions.fresh
import org.mobilenativefoundation.store.store5.util.FakeFetcher
import org.mobilenativefoundation.store.store5.util.FakeFlowingFetcher
import org.mobilenativefoundation.store.store5.util.InMemoryPersister
import org.mobilenativefoundation.store.store5.util.asFlowable
import org.mobilenativefoundation.store.store5.util.asSourceOfTruth
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

@FlowPreview
@ExperimentalCoroutinesApi
class FlowStoreTests {
    private val testScope = TestScope()

    @Test
    fun getAndFresh() =
        testScope.runTest {
            val fetcher =
                FakeFetcher(
                    3 to "three-1",
                    3 to "three-2",
                )
            val pipeline =
                StoreBuilder
                    .from(fetcher)
                    .buildWithTestScope()

            assertEquals(
                pipeline.stream(StoreReadRequest.cached(3, refresh = false)).take(2).toList(),
                listOf(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                ),
            )

            assertEquals(
                pipeline.stream(StoreReadRequest.cached(3, refresh = false)).take(1).toList(),
                listOf(
                    Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.Cache,
                    ),
                ),
            )

            assertEquals(
                pipeline.stream(StoreReadRequest.fresh(3)).take(2).toList(),
                listOf(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                ),
            )

            assertEquals(
                pipeline.stream(StoreReadRequest.cached(3, refresh = false)).take(1).toList(),
                listOf(
                    Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.Cache,
                    ),
                ),
            )
        }

    @Test
    fun getAndFresh_withPersister() =
        testScope.runTest {
            val fetcher =
                FakeFetcher(
                    3 to "three-1",
                    3 to "three-2",
                )
            val persister = InMemoryPersister<Int, String>()
            val pipeline =
                StoreBuilder.from(
                    fetcher = fetcher,
                    sourceOfTruth = persister.asSourceOfTruth(),
                ).buildWithTestScope()

            pipeline.stream(StoreReadRequest.cached(3, refresh = false)).test {
                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }

            pipeline.stream(StoreReadRequest.cached(3, refresh = false)).test {
                assertEquals(
                    Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.Cache,
                    ),
                    awaitItem(),
                )

                // note that we still get the data from persister as well as we don't listen to
                // the persister for the cached items unless there is an active stream, which
                // means cache can go out of sync w/ the persister

                assertEquals(
                    Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )
            }

            pipeline.stream(StoreReadRequest.fresh(3)).test {
                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }

            pipeline.stream(StoreReadRequest.cached(3, refresh = false)).test {
                assertEquals(
                    Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.Cache,
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )
            }
        }

    @Test
    fun streamAndFresh_withPersister() =
        testScope.runTest {
            val fetcher =
                FakeFetcher(
                    3 to "three-1",
                    3 to "three-2",
                )
            val persister = InMemoryPersister<Int, String>()

            val pipeline =
                StoreBuilder.from(
                    fetcher = fetcher,
                    sourceOfTruth = persister.asSourceOfTruth(),
                ).buildWithTestScope()

            pipeline.stream(StoreReadRequest.cached(3, refresh = true)).test {
                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }

            pipeline.stream(StoreReadRequest.cached(3, refresh = true)).test {
                assertEquals(
                    Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.Cache,
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }
        }

    @Test
    fun streamAndFresh() =
        testScope.runTest {
            val fetcher =
                FakeFetcher(
                    3 to "three-1",
                    3 to "three-2",
                )
            val pipeline =
                StoreBuilder.from(fetcher = fetcher)
                    .buildWithTestScope()

            pipeline.stream(StoreReadRequest.cached(3, refresh = true)).test {
                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }

            pipeline.stream(StoreReadRequest.cached(3, refresh = true)).test {
                assertEquals(
                    Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.Cache,
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }
        }

    @Test
    fun skipCache() =
        testScope.runTest {
            val fetcher =
                FakeFetcher(
                    3 to "three-1",
                    3 to "three-2",
                )
            val pipeline =
                StoreBuilder.from(fetcher = fetcher)
                    .buildWithTestScope()

            pipeline.stream(StoreReadRequest.skipMemory(3, refresh = false)).test {
                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }

            pipeline.stream(StoreReadRequest.skipMemory(3, refresh = false)).test {
                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }
        }

    @Test
    fun flowingFetcher() =
        testScope.runTest {
            val fetcher =
                FakeFlowingFetcher(
                    3 to "three-1",
                    3 to "three-2",
                )
            val persister = InMemoryPersister<Int, String>()

            val pipeline =
                StoreBuilder.from(
                    fetcher = fetcher,
                    sourceOfTruth = persister.asSourceOfTruth(),
                )
                    .disableCache()
                    .buildWithTestScope()

            pipeline.stream(StoreReadRequest.fresh(3)).test {
                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }

            pipeline.stream(StoreReadRequest.cached(3, refresh = true)).test {
                assertEquals(
                    Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }
        }

    @Test
    fun diskChangeWhileNetworkIsFlowing_simple() =
        testScope.runTest {
            val persister = InMemoryPersister<Int, String>().asFlowable()
            val pipeline =
                StoreBuilder.from(
                    Fetcher.ofFlow {
                        flow {
                            delay(20)
                            emit("three-1")
                        }
                    },
                    sourceOfTruth = persister.asSourceOfTruth(),
                )
                    .disableCache()
                    .buildWithTestScope()

            launch {
                delay(10)
                persister.flowWriter(3, "local-1")
            }

            pipeline.stream(StoreReadRequest.cached(3, refresh = true)).test {
                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "local-1",
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }
        }

    @Test
    fun diskChangeWhileNetworkIsFlowing_overwrite() =
        testScope.runTest {
            val persister = InMemoryPersister<Int, String>().asFlowable()
            val pipeline =
                StoreBuilder.from(
                    fetcher =
                        Fetcher.ofFlow {
                            flow {
                                delay(10)
                                emit("three-1")
                                delay(10)
                                emit("three-2")
                            }
                        },
                    sourceOfTruth = persister.asSourceOfTruth(),
                )
                    .disableCache()
                    .buildWithTestScope()

            launch {
                delay(5)
                persister.flowWriter(3, "local-1")
                delay(10) // go in between two server requests
                persister.flowWriter(3, "local-2")
            }

            pipeline.stream(StoreReadRequest.cached(3, refresh = true)).test {
                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "local-1",
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-1",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "local-2",
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "three-2",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }
        }

    @Test
    fun errorTest() =
        testScope.runTest {
            val exception = IllegalArgumentException("wow")
            val persister = InMemoryPersister<Int, String>().asFlowable()
            val pipeline =
                StoreBuilder.from(
                    Fetcher.of {
                        throw exception
                    },
                    sourceOfTruth = persister.asSourceOfTruth(),
                )
                    .disableCache()
                    .buildWithTestScope()

            launch {
                delay(10)
                persister.flowWriter(3, "local-1")
            }

            pipeline.stream(StoreReadRequest.cached(key = 3, refresh = true)).test {
                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    StoreReadResponse.Error.Exception(
                        error = exception,
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "local-1",
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )
            }

            pipeline.stream(StoreReadRequest.cached(key = 3, refresh = true)).test {
                assertEquals(
                    Data(
                        value = "local-1",
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    StoreReadResponse.Error.Exception(
                        error = exception,
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }
        }

    @Test
    fun givenSourceOfTruthWhenStreamFreshDataReturnsNoDataFromFetcherThenFetchReturnsNoDataAndCachedValuesAreReceived() =
        testScope.runTest {
            val persister = InMemoryPersister<Int, String>().asFlowable()
            val pipeline =
                StoreBuilder.from(
                    fetcher = Fetcher.ofFlow { flow {} },
                    sourceOfTruth = persister.asSourceOfTruth(),
                )
                    .buildWithTestScope()

            persister.flowWriter(3, "local-1")
            val firstFetch = pipeline.fresh(3) // prime the cache
            assertEquals("local-1", firstFetch)

            pipeline.stream(StoreReadRequest.fresh(3)).test {
                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    StoreReadResponse.NoNewData(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "local-1",
                        origin = StoreReadResponseOrigin.Cache,
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "local-1",
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )
            }
        }

    @Test
    fun givenSourceOfTruthWhenStreamCachedDataWithRefreshReturnsNoNewDataThenCachedValuesAreReceivedAndFetchReturnsNoData() =
        testScope.runTest {
            val persister = InMemoryPersister<Int, String>().asFlowable()
            val pipeline =
                StoreBuilder.from(
                    fetcher = Fetcher.ofFlow { flow {} },
                    sourceOfTruth = persister.asSourceOfTruth(),
                )
                    .buildWithTestScope()

            persister.flowWriter(3, "local-1")
            val firstFetch = pipeline.fresh(3) // prime the cache
            assertEquals("local-1", firstFetch)

            pipeline.stream(StoreReadRequest.cached(3, refresh = true)).test {
                assertEquals(
                    Data(
                        value = "local-1",
                        origin = StoreReadResponseOrigin.Cache,
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "local-1",
                        origin = StoreReadResponseOrigin.SourceOfTruth,
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    StoreReadResponse.NoNewData(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }
        }

    @Test
    fun givenNoSourceOfTruthWhenStreamFreshDataReturnsNoDataFromFetcherThenFetchReturnsNoDataAndCachedValuesAreReceived() =
        testScope.runTest {
            var createCount = 0
            val pipeline =
                StoreBuilder.from(
                    fetcher =
                        Fetcher.ofFlow {
                            if (createCount++ == 0) {
                                flowOf("remote-1")
                            } else {
                                flowOf()
                            }
                        },
                )
                    .buildWithTestScope()

            val firstFetch = pipeline.fresh(3) // prime the cache
            assertEquals("remote-1", firstFetch)

            pipeline.stream(StoreReadRequest.fresh(3)).test {
                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    StoreReadResponse.NoNewData(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Data(
                        value = "remote-1",
                        origin = StoreReadResponseOrigin.Cache,
                    ),
                    awaitItem(),
                )
            }
        }

    @Test
    fun givenNoSoTWhenStreamCachedDataWithRefreshReturnsNoNewDataThenCachedValuesAreReceivedAndFetchReturnsNoData() =
        testScope.runTest {
            var createCount = 0
            val pipeline =
                StoreBuilder.from(
                    fetcher =
                        Fetcher.ofFlow {
                            if (createCount++ == 0) {
                                flowOf("remote-1")
                            } else {
                                flowOf()
                            }
                        },
                )
                    .buildWithTestScope()

            val firstFetch = pipeline.fresh(3) // prime the cache
            assertEquals("remote-1", firstFetch)

            pipeline.stream(StoreReadRequest.cached(3, refresh = true)).test {
                assertEquals(
                    Data(
                        value = "remote-1",
                        origin = StoreReadResponseOrigin.Cache,
                    ),
                    awaitItem(),
                )

                assertEquals(
                    Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )

                assertEquals(
                    StoreReadResponse.NoNewData(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    awaitItem(),
                )
            }
        }

    @Test
    fun givenNoSourceOfTruthAndCacheHitWhenStreamCachedDataWithoutRefreshThenNoFetchIsTriggeredAndReceivesFollowingNetworkUpdates() =
        testScope.runTest {
            val fetcher =
                FakeFetcher(
                    3 to "three-1",
                    3 to "three-2",
                )
            val store =
                StoreBuilder.from(fetcher = fetcher)
                    .buildWithTestScope()

            val firstFetch = store.fresh(3)
            assertEquals("three-1", firstFetch)
            val secondCollect = mutableListOf<StoreReadResponse<String>>()
            val collection =
                launch {
                    store.stream(StoreReadRequest.cached(3, refresh = false)).collect {
                        secondCollect.add(it)
                    }
                }
            testScope.runCurrent()
            assertEquals(1, secondCollect.size)
            assertContains(
                secondCollect,
                Data(
                    value = "three-1",
                    origin = StoreReadResponseOrigin.Cache,
                ),
            )
            // trigger another fetch from network
            val secondFetch = store.fresh(3)
            assertEquals("three-2", secondFetch)
            testScope.runCurrent()
            // make sure cached also received it
            assertEquals(2, secondCollect.size)

            assertContains(
                secondCollect,
                Data(
                    value = "three-1",
                    origin = StoreReadResponseOrigin.Cache,
                ),
            )
            assertContains(
                secondCollect,
                Data(
                    value = "three-2",
                    origin = StoreReadResponseOrigin.Fetcher(),
                ),
            )

            collection.cancelAndJoin()
        }

    @Test
    fun givenSourceOfTruthAndCacheHitWhenStreamCachedDataWithoutRefreshThenNoFetchIsTriggeredAndReceivesFollowingNetworkUpdates() =
        testScope.runTest {
            val fetcher =
                FakeFetcher(
                    3 to "three-1",
                    3 to "three-2",
                )
            val persister = InMemoryPersister<Int, String>()
            val pipeline =
                StoreBuilder.from(
                    fetcher = fetcher,
                    sourceOfTruth = persister.asSourceOfTruth(),
                ).buildWithTestScope()

            val firstFetch = pipeline.fresh(3)
            assertEquals("three-1", firstFetch)
            val secondCollect = mutableListOf<StoreReadResponse<String>>()
            val collection =
                launch {
                    pipeline.stream(StoreReadRequest.cached(3, refresh = false)).collect {
                        secondCollect.add(it)
                    }
                }
            testScope.runCurrent()
            assertEquals(2, secondCollect.size)

            assertContains(
                secondCollect,
                Data(
                    value = "three-1",
                    origin = StoreReadResponseOrigin.Cache,
                ),
            )
            assertContains(
                secondCollect,
                Data(
                    value = "three-1",
                    origin = StoreReadResponseOrigin.SourceOfTruth,
                ),
            )

            // trigger another fetch from network
            val secondFetch = pipeline.fresh(3)
            assertEquals("three-2", secondFetch)
            testScope.runCurrent()
            // make sure cached also received it
            assertEquals(3, secondCollect.size)

            assertContains(
                secondCollect,
                Data(
                    value = "three-1",
                    origin = StoreReadResponseOrigin.Cache,
                ),
            )
            assertContains(
                secondCollect,
                Data(
                    value = "three-1",
                    origin = StoreReadResponseOrigin.SourceOfTruth,
                ),
            )

            assertContains(
                secondCollect,
                Data(
                    value = "three-2",
                    origin = StoreReadResponseOrigin.Fetcher(),
                ),
            )
            collection.cancelAndJoin()
        }

    @Test
    fun testSlowFirstCollectorGetsAllFetchUpdatesOthersGetCacheAndLatestFetchResult() =
        testScope.runTest {
            val fetcher =
                FakeFetcher(
                    3 to "three-1",
                    3 to "three-2",
                    3 to "three-3",
                )
            val pipeline =
                StoreBuilder.from(
                    fetcher = fetcher,
                ).buildWithTestScope()

            val fetcher1Collected = mutableListOf<StoreReadResponse<String>>()
            val fetcher1Job =
                async {
                    pipeline.stream(StoreReadRequest.cached(3, refresh = true)).collect {
                        fetcher1Collected.add(it)
                        delay(1_000)
                    }
                }
            testScope.advanceUntilIdle()
            assertEquals(
                listOf(
                    Loading(origin = StoreReadResponseOrigin.Fetcher()),
                    Data(origin = StoreReadResponseOrigin.Fetcher(), value = "three-1"),
                ),
                fetcher1Collected,
            )

            pipeline.stream(StoreReadRequest.cached(3, refresh = true)).test {
                assertEquals(
                    Data(origin = StoreReadResponseOrigin.Cache, value = "three-1"),
                    awaitItem(),
                )
                assertEquals(
                    Loading(origin = StoreReadResponseOrigin.Fetcher()),
                    awaitItem(),
                )

                assertEquals(
                    Data(origin = StoreReadResponseOrigin.Fetcher(), value = "three-2"),
                    awaitItem(),
                )
            }

            pipeline.stream(StoreReadRequest.cached(3, refresh = true)).test {
                assertEquals(
                    Data(origin = StoreReadResponseOrigin.Cache, value = "three-2"),
                    awaitItem(),
                )

                assertEquals(
                    Loading(origin = StoreReadResponseOrigin.Fetcher()),
                    awaitItem(),
                )
                assertEquals(
                    Data(origin = StoreReadResponseOrigin.Fetcher(), value = "three-3"),
                    awaitItem(),
                )
            }

            testScope.advanceUntilIdle()
            assertEquals(
                listOf(
                    Loading(origin = StoreReadResponseOrigin.Fetcher()),
                    Data(origin = StoreReadResponseOrigin.Fetcher(), value = "three-1"),
                    Data(origin = StoreReadResponseOrigin.Fetcher(), value = "three-2"),
                    Data(origin = StoreReadResponseOrigin.Fetcher(), value = "three-3"),
                ),
                fetcher1Collected,
            )

            fetcher1Job.cancelAndJoin()
        }

    @Test
    fun testFirstStreamGetsTwoFetchUpdatesSecondGetsCacheAndFetchResult() =
        testScope.runTest {
            val fetcher =
                FakeFetcher(
                    3 to "three-1",
                    3 to "three-2",
                )
            val pipeline =
                StoreBuilder.from(fetcher = fetcher)
                    .buildWithTestScope()

            val fetcher1Collected = mutableListOf<StoreReadResponse<String>>()
            val fetcher1Job =
                async {
                    pipeline.stream(StoreReadRequest.cached(3, refresh = true)).collect {
                        fetcher1Collected.add(it)
                    }
                }
            testScope.runCurrent()
            assertEquals(
                listOf(
                    Loading(origin = StoreReadResponseOrigin.Fetcher()),
                    Data(origin = StoreReadResponseOrigin.Fetcher(), value = "three-1"),
                ),
                fetcher1Collected,
            )

            pipeline.stream(StoreReadRequest.cached(3, refresh = true)).test {
                assertEquals(
                    Data(origin = StoreReadResponseOrigin.Cache, value = "three-1"),
                    awaitItem(),
                )

                assertEquals(
                    Loading(origin = StoreReadResponseOrigin.Fetcher()),
                    awaitItem(),
                )

                assertEquals(
                    Data(origin = StoreReadResponseOrigin.Fetcher(), value = "three-2"),
                    awaitItem(),
                )
            }

            testScope.runCurrent()
            assertEquals(
                listOf(
                    Loading(origin = StoreReadResponseOrigin.Fetcher()),
                    Data(origin = StoreReadResponseOrigin.Fetcher(), value = "three-1"),
                    Data(origin = StoreReadResponseOrigin.Fetcher(), value = "three-2"),
                ),
                fetcher1Collected,
            )

            fetcher1Job.cancelAndJoin()
        }

    suspend fun Store<Int, Int>.get(request: StoreReadRequest<Int>) = this.stream(request).filter { it.dataOrNull() != null }.first()

    suspend fun Store<Int, Int>.get(key: Int) =
        get(
            StoreReadRequest.cached(
                key = key,
                refresh = false,
            ),
        )

    private fun <Key : Any, Output : Any> StoreBuilder<Key, Output>.buildWithTestScope() = scope(testScope).build()
}
