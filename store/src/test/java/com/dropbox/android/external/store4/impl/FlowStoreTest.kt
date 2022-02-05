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
package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.android.external.store4.StoreResponse.Data
import com.dropbox.android.external.store4.StoreResponse.Loading
import com.dropbox.android.external.store4.fresh
import com.dropbox.android.external.store4.testutil.FakeFetcher
import com.dropbox.android.external.store4.testutil.FakeFlowingFetcher
import com.dropbox.android.external.store4.testutil.InMemoryPersister
import com.dropbox.android.external.store4.testutil.asFlowable
import com.dropbox.android.external.store4.testutil.asSourceOfTruth
import com.dropbox.android.external.store4.testutil.assertThat
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@FlowPreview
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class FlowStoreTest {
    private val testScope = TestCoroutineScope()

    @Test
    fun getAndFresh() = testScope.runBlockingTest {
        val fetcher = FakeFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val pipeline = StoreBuilder
            .from(fetcher)
            .buildWithTestScope()

        assertThat(pipeline.stream(StoreRequest.cached(3, refresh = false)))
            .emitsExactly(
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Fetcher
                )
            )
        assertThat(
            pipeline.stream(StoreRequest.cached(3, refresh = false))
        ).emitsExactly(
            Data(
                value = "three-1",
                origin = ResponseOrigin.Cache
            )
        )
        assertThat(pipeline.stream(StoreRequest.fresh(3)))
            .emitsExactly(
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = ResponseOrigin.Fetcher
                )
            )
        assertThat(
            pipeline.stream(StoreRequest.cached(3, refresh = false))
        )
            .emitsExactly(
                Data(
                    value = "three-2",
                    origin = ResponseOrigin.Cache
                )
            )
    }

    @Test
    fun getAndFresh_withPersister() = testScope.runBlockingTest {
        val fetcher = FakeFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val persister = InMemoryPersister<Int, String>()
        val pipeline = StoreBuilder.from(
            fetcher = fetcher,
            sourceOfTruth = persister.asSourceOfTruth()
        ).buildWithTestScope()

        assertThat(pipeline.stream(StoreRequest.cached(3, refresh = false)))
            .emitsExactly(
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Fetcher
                )
            )
        assertThat(pipeline.stream(StoreRequest.cached(3, refresh = false)))
            .emitsExactly(
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Cache
                ),
                // note that we still get the data from persister as well as we don't listen to
                // the persister for the cached items unless there is an active stream, which
                // means cache can go out of sync w/ the persister
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.SourceOfTruth
                )
            )
        assertThat(pipeline.stream(StoreRequest.fresh(3)))
            .emitsExactly(
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = ResponseOrigin.Fetcher
                )
            )
        assertThat(pipeline.stream(StoreRequest.cached(3, refresh = false)))
            .emitsExactly(
                Data(
                    value = "three-2",
                    origin = ResponseOrigin.Cache
                ),
                Data(
                    value = "three-2",
                    origin = ResponseOrigin.SourceOfTruth
                )
            )
    }

    @Test
    fun streamAndFresh_withPersister() = testScope.runBlockingTest {
        val fetcher = FakeFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val persister = InMemoryPersister<Int, String>()

        val pipeline = StoreBuilder.from(
            fetcher = fetcher,
            sourceOfTruth = persister.asSourceOfTruth()
        ).buildWithTestScope()

        assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
            .emitsExactly(
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Fetcher
                )
            )

        assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
            .emitsExactly(
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Cache
                ),
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.SourceOfTruth
                ),
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = ResponseOrigin.Fetcher
                )
            )
    }

    @Test
    fun streamAndFresh() = testScope.runBlockingTest {
        val fetcher = FakeFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val pipeline = StoreBuilder.from(fetcher = fetcher)
            .buildWithTestScope()

        assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
            .emitsExactly(
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Fetcher
                )
            )

        assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
            .emitsExactly(
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Cache
                ),
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = ResponseOrigin.Fetcher
                )
            )
    }

    @Test
    fun skipCache() = testScope.runBlockingTest {
        val fetcher = FakeFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val pipeline = StoreBuilder.from(fetcher = fetcher)
            .buildWithTestScope()

        assertThat(pipeline.stream(StoreRequest.skipMemory(3, refresh = false)))
            .emitsExactly(
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Fetcher
                )
            )

        assertThat(pipeline.stream(StoreRequest.skipMemory(3, refresh = false)))
            .emitsExactly(
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = ResponseOrigin.Fetcher
                )
            )
    }

    @Test
    fun flowingFetcher() = testScope.runBlockingTest {
        val fetcher = FakeFlowingFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val persister = InMemoryPersister<Int, String>()

        val pipeline = StoreBuilder.from(
            fetcher = fetcher,
            sourceOfTruth = persister.asSourceOfTruth()
        )
            .disableCache()
            .buildWithTestScope()

        assertThat(pipeline.stream(StoreRequest.fresh(3)))
            .emitsExactly(
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = ResponseOrigin.Fetcher
                )
            )
        assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
            .emitsExactly(
                Data(
                    value = "three-2",
                    origin = ResponseOrigin.SourceOfTruth
                ),
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = ResponseOrigin.Fetcher
                )
            )
    }

    @Test
    fun diskChangeWhileNetworkIsFlowing_simple() = testScope.runBlockingTest {
        val persister = InMemoryPersister<Int, String>().asFlowable()
        val pipeline = StoreBuilder.from(
            Fetcher.ofFlow {
                flow {
                    delay(20)
                    emit("three-1")
                }
            },
            sourceOfTruth = persister.asSourceOfTruth()
        )
            .disableCache()
            .buildWithTestScope()

        launch {
            delay(10)
            persister.flowWriter(3, "local-1")
        }
        assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
            .emitsExactly(
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "local-1",
                    origin = ResponseOrigin.SourceOfTruth
                ),
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Fetcher
                )

            )
    }

    @Test
    fun diskChangeWhileNetworkIsFlowing_overwrite() = testScope.runBlockingTest {
        val persister = InMemoryPersister<Int, String>().asFlowable()
        val pipeline = StoreBuilder.from(
            fetcher = Fetcher.ofFlow {
                flow {
                    delay(10)
                    emit("three-1")
                    delay(10)
                    emit("three-2")
                }
            },
            sourceOfTruth = persister.asSourceOfTruth()
        )
            .disableCache()
            .buildWithTestScope()

        launch {
            delay(5)
            persister.flowWriter(3, "local-1")
            delay(10) // go in between two server requests
            persister.flowWriter(3, "local-2")
        }
        assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
            .emitsExactly(
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "local-1",
                    origin = ResponseOrigin.SourceOfTruth
                ),
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "local-2",
                    origin = ResponseOrigin.SourceOfTruth
                ),
                Data(
                    value = "three-2",
                    origin = ResponseOrigin.Fetcher
                )
            )
    }

    @Test
    fun errorTest() = testScope.runBlockingTest {
        val exception = IllegalArgumentException("wow")
        val persister = InMemoryPersister<Int, String>().asFlowable()
        val pipeline = StoreBuilder.from(
            Fetcher.of {
                throw exception
            },
            sourceOfTruth = persister.asSourceOfTruth()
        )
            .disableCache()
            .buildWithTestScope()

        launch {
            delay(10)
            persister.flowWriter(3, "local-1")
        }
        assertThat(pipeline.stream(StoreRequest.cached(key = 3, refresh = true)))
            .emitsExactly(
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Error.Exception(
                    error = exception,
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "local-1",
                    origin = ResponseOrigin.SourceOfTruth
                )
            )
        assertThat(pipeline.stream(StoreRequest.cached(key = 3, refresh = true)))
            .emitsExactly(
                Data(
                    value = "local-1",
                    origin = ResponseOrigin.SourceOfTruth
                ),
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Error.Exception(
                    error = exception,
                    origin = ResponseOrigin.Fetcher
                )
            )
    }

    @Test
    fun `GIVEN SoT WHEN stream fresh data returns no data from fetcher THEN fetch returns no data AND cached values are recevied`() =
        testScope.runBlockingTest {
            val persister = InMemoryPersister<Int, String>().asFlowable()
            val pipeline = StoreBuilder.from(
                fetcher = Fetcher.ofFlow { flow {} },
                sourceOfTruth = persister.asSourceOfTruth()
            )
                .buildWithTestScope()

            persister.flowWriter(3, "local-1")
            val firstFetch = pipeline.fresh(3) // prime the cache
            assertThat(firstFetch).isEqualTo("local-1")

            assertThat(pipeline.stream(StoreRequest.fresh(3)))
                .emitsExactly(
                    Loading(
                        origin = ResponseOrigin.Fetcher
                    ),
                    StoreResponse.NoNewData(
                        origin = ResponseOrigin.Fetcher
                    ),
                    Data(
                        value = "local-1",
                        origin = ResponseOrigin.Cache
                    ),
                    Data(
                        value = "local-1",
                        origin = ResponseOrigin.SourceOfTruth
                    )
                )
        }

    @Test
    fun `GIVEN SoT WHEN stream cached data with refresh returns NoNewData THEN cached values are recevied AND fetch returns no data`() =
        testScope.runBlockingTest {
            val persister = InMemoryPersister<Int, String>().asFlowable()
            val pipeline = StoreBuilder.from(
                fetcher = Fetcher.ofFlow { flow {} },
                sourceOfTruth = persister.asSourceOfTruth()
            )
                .buildWithTestScope()

            persister.flowWriter(3, "local-1")
            val firstFetch = pipeline.fresh(3) // prime the cache
            assertThat(firstFetch).isEqualTo("local-1")

            assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
                .emitsExactly(
                    Data(
                        value = "local-1",
                        origin = ResponseOrigin.Cache
                    ),
                    Data(
                        value = "local-1",
                        origin = ResponseOrigin.SourceOfTruth
                    ),
                    Loading(
                        origin = ResponseOrigin.Fetcher
                    ),
                    StoreResponse.NoNewData(
                        origin = ResponseOrigin.Fetcher
                    )
                )
        }

    @Test
    fun `GIVEN no SoT WHEN stream fresh data returns no data from fetcher THEN fetch returns no data AND cached values are recevied`() =
        testScope.runBlockingTest {
            var createCount = 0
            val pipeline = StoreBuilder.from<Int, String>(
                fetcher = Fetcher.ofFlow {
                    if (createCount++ == 0) {
                        flowOf("remote-1")
                    } else {
                        flowOf()
                    }
                }
            )
                .buildWithTestScope()

            val firstFetch = pipeline.fresh(3) // prime the cache
            assertThat(firstFetch).isEqualTo("remote-1")

            assertThat(pipeline.stream(StoreRequest.fresh(3)))
                .emitsExactly(
                    Loading(
                        origin = ResponseOrigin.Fetcher
                    ),
                    StoreResponse.NoNewData(
                        origin = ResponseOrigin.Fetcher
                    ),
                    Data(
                        value = "remote-1",
                        origin = ResponseOrigin.Cache
                    )
                )
        }

    @Test
    fun `GIVEN no SoT WHEN stream cached data with refresh returns NoNewData THEN cached values are recevied AND fetch returns no data`() =
        testScope.runBlockingTest {
            var createCount = 0
            val pipeline = StoreBuilder.from<Int, String>(
                fetcher = Fetcher.ofFlow {
                    if (createCount++ == 0) {
                        flowOf("remote-1")
                    } else {
                        flowOf()
                    }
                }
            )
                .buildWithTestScope()

            val firstFetch = pipeline.fresh(3) // prime the cache
            assertThat(firstFetch).isEqualTo("remote-1")

            assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
                .emitsExactly(
                    Data(
                        value = "remote-1",
                        origin = ResponseOrigin.Cache
                    ),
                    Loading(
                        origin = ResponseOrigin.Fetcher
                    ),
                    StoreResponse.NoNewData(
                        origin = ResponseOrigin.Fetcher
                    )
                )
        }

    @Test
    fun `GIVEN no sourceOfTruth and cache hit WHEN stream cached data without refresh THEN no fetch is triggered AND receives following network updates`() =
        testScope.runBlockingTest {
            val fetcher = FakeFetcher(
                3 to "three-1",
                3 to "three-2"
            )
            val store = StoreBuilder.from(fetcher = fetcher)
                .buildWithTestScope()

            val firstFetch = store.fresh(3)
            assertThat(firstFetch).isEqualTo("three-1")
            val secondCollect = mutableListOf<StoreResponse<String>>()
            val collection = launch {
                store.stream(StoreRequest.cached(3, refresh = false)).collect {
                    secondCollect.add(it)
                }
            }
            testScope.runCurrent()
            assertThat(secondCollect).containsExactly(
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Cache
                )
            )
            // trigger another fetch from network
            val secondFetch = store.fresh(3)
            assertThat(secondFetch).isEqualTo("three-2")
            testScope.runCurrent()
            // make sure cached also received it
            assertThat(secondCollect).containsExactly(
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Cache
                ),
                Data(
                    value = "three-2",
                    origin = ResponseOrigin.Fetcher
                )
            )
            collection.cancelAndJoin()
        }

    @Test
    fun `GIVEN sourceOfTruth and cache hit WHEN stream cached data without refresh THEN no fetch is triggered AND receives following network updates`() =
        testScope.runBlockingTest {
            val fetcher = FakeFetcher(
                3 to "three-1",
                3 to "three-2"
            )
            val persister = InMemoryPersister<Int, String>()
            val pipeline = StoreBuilder.from(
                fetcher = fetcher,
                sourceOfTruth = persister.asSourceOfTruth()
            ).buildWithTestScope()

            val firstFetch = pipeline.fresh(3)
            assertThat(firstFetch).isEqualTo("three-1")
            val secondCollect = mutableListOf<StoreResponse<String>>()
            val collection = launch {
                pipeline.stream(StoreRequest.cached(3, refresh = false)).collect {
                    secondCollect.add(it)
                }
            }
            testScope.runCurrent()
            assertThat(secondCollect).containsExactly(
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Cache
                ),
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.SourceOfTruth
                )
            )
            // trigger another fetch from network
            val secondFetch = pipeline.fresh(3)
            assertThat(secondFetch).isEqualTo("three-2")
            testScope.runCurrent()
            // make sure cached also received it
            assertThat(secondCollect).containsExactly(
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Cache
                ),
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.SourceOfTruth
                ),
                Data(
                    value = "three-2",
                    origin = ResponseOrigin.Fetcher
                )
            )
            collection.cancelAndJoin()
        }

    @Test
    fun `GIVEN cache and no sourceOfTruth WHEN 3 cached streams with refresh AND 1st has slow collection THEN 1st streams gets 3 fetch updates AND other streams get cache result AND fetch result`() =
        testScope.runBlockingTest {
            val fetcher = FakeFetcher(
                3 to "three-1",
                3 to "three-2",
                3 to "three-3"
            )
            val pipeline = StoreBuilder.from(
                fetcher = fetcher
            ).buildWithTestScope()

            val fetcher1Collected = mutableListOf<StoreResponse<String>>()
            val fetcher1Job = async {
                pipeline.stream(StoreRequest.cached(3, refresh = true)).collect {
                    fetcher1Collected.add(it)
                    delay(1_000)
                }
            }
            testScope.advanceUntilIdle()
            assertThat(fetcher1Collected).isEqualTo(
                listOf(
                    Loading(origin = ResponseOrigin.Fetcher),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-1")
                )
            )
            assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
                .emitsExactly(
                    Data(origin = ResponseOrigin.Cache, value = "three-1"),
                    Loading(origin = ResponseOrigin.Fetcher),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-2")
                )
            assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
                .emitsExactly(
                    Data(origin = ResponseOrigin.Cache, value = "three-2"),
                    Loading(origin = ResponseOrigin.Fetcher),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-3")
                )
            testScope.advanceUntilIdle()
            assertThat(fetcher1Collected).isEqualTo(
                listOf(
                    Loading(origin = ResponseOrigin.Fetcher),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-1"),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-2"),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-3")
                )
            )
            fetcher1Job.cancelAndJoin()
        }

    @Test
    fun `GIVEN cache and no sourceOfTruth WHEN 2 cached streams with refresh THEN first streams gets 2 fetch updates AND 2nd stream gets cache result and fetch result`() =
        testScope.runBlockingTest {
            val fetcher = FakeFetcher(
                3 to "three-1",
                3 to "three-2"
            )
            val pipeline = StoreBuilder.from(fetcher = fetcher)
                .buildWithTestScope()

            val fetcher1Collected = mutableListOf<StoreResponse<String>>()
            val fetcher1Job = async {
                pipeline.stream(StoreRequest.cached(3, refresh = true)).collect {
                    fetcher1Collected.add(it)
                }
            }
            testScope.runCurrent()
            assertThat(fetcher1Collected).isEqualTo(
                listOf(
                    Loading(origin = ResponseOrigin.Fetcher),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-1")
                )
            )
            assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
                .emitsExactly(
                    Data(origin = ResponseOrigin.Cache, value = "three-1"),
                    Loading(origin = ResponseOrigin.Fetcher),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-2")
                )
            testScope.runCurrent()
            assertThat(fetcher1Collected).isEqualTo(
                listOf(
                    Loading(origin = ResponseOrigin.Fetcher),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-1"),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-2")
                )
            )
            fetcher1Job.cancelAndJoin()
        }

    @Test
    fun `GIVEN SoT has no data and fetcher throws an error WHEN stream with fresh request and fallbackToSourceOfTruth THEN return fetcher error`() =
        testScope.runBlockingTest {
            val exception = IllegalArgumentException("wow")
            val persister = InMemoryPersister<Int, String>().asFlowable()
            val pipeline = StoreBuilder.from(
                Fetcher.of {
                    throw exception
                },
                sourceOfTruth = persister.asSourceOfTruth()
            )
                .disableCache()
                .buildWithTestScope()

            assertThat(pipeline.stream(StoreRequest.fresh(key = 3, fallbackToSourceOfTruth = true)))
                .emitsExactly(
                    Loading(
                        origin = ResponseOrigin.Fetcher
                    ),
                    StoreResponse.Error.Exception(
                        error = exception,
                        origin = ResponseOrigin.Fetcher
                    )
                )
        }

    @Test
    fun `GIVEN SoT has data and fetcher throws an error WHEN stream with fresh request and fallbackToSourceOfTruth THEN return fetcher error followed by SoT data`() =
        testScope.runBlockingTest {
            val exception = IllegalArgumentException("wow")
            val persister = InMemoryPersister<Int, String>().asFlowable()
            val pipeline = StoreBuilder.from(
                Fetcher.of {
                    throw exception
                },
                sourceOfTruth = persister.asSourceOfTruth()
            )
                .disableCache()
                .buildWithTestScope()

            persister.flowWriter(3, "local-1")

            assertThat(pipeline.stream(StoreRequest.fresh(key = 3, fallbackToSourceOfTruth = true)))
                .emitsExactly(
                    Loading(
                        origin = ResponseOrigin.Fetcher
                    ),
                    StoreResponse.Error.Exception(
                        error = exception,
                        origin = ResponseOrigin.Fetcher
                    ),
                    Data(
                        value = "local-1",
                        origin = ResponseOrigin.SourceOfTruth
                    )
                )
        }

    suspend fun Store<Int, String>.get(request: StoreRequest<Int>) =
        this.stream(request).filter { it.dataOrNull() != null }.first()

    suspend fun Store<Int, String>.get(key: Int) = get(
        StoreRequest.cached(
            key = key,
            refresh = false
        )
    )

    private fun <Key : Any, Output : Any> StoreBuilder<Key, Output>.buildWithTestScope() =
        scope(testScope).build()
}
