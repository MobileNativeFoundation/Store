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
import com.dropbox.android.external.store4.ResponseOrigin.Cache
import com.dropbox.android.external.store4.ResponseOrigin.Persister
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.android.external.store4.StoreResponse.Data
import com.dropbox.android.external.store4.StoreResponse.Loading
import com.dropbox.android.external.store4.fresh
import com.dropbox.android.external.store4.testutil.FakeFetcher
import com.dropbox.android.external.store4.testutil.InMemoryPersister
import com.dropbox.android.external.store4.testutil.asFlowable
import com.dropbox.android.external.store4.testutil.assertThat
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@ExperimentalStdlibApi
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
        val pipeline = build<Int, String, String>(
            nonFlowingFetcher = fetcher::fetch,
            enableCache = true
        )
        assertThat(pipeline.stream(StoreRequest.cached(3, refresh = false)))
            .emitsExactly(
                Loading(
                    origin = ResponseOrigin.Fetcher
                ), Data(
                    value = "three-1",
                    origin = ResponseOrigin.Fetcher
                )
            )
        assertThat(
            pipeline.stream(StoreRequest.cached(3, refresh = false))
        ).emitsExactly(
            Data(
                value = "three-1",
                origin = Cache
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
                    origin = Cache
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
        val pipeline = build(
            nonFlowingFetcher = fetcher::fetch,
            persisterReader = persister::read,
            persisterWriter = persister::write,
            enableCache = true
        )
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
                    origin = Cache
                ),
                // note that we still get the data from persister as well as we don't listen to
                // the persister for the cached items unless there is an active stream, which
                // means cache can go out of sync w/ the persister
                Data(
                    value = "three-1",
                    origin = Persister
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
                    origin = Cache
                ),
                Data(
                    value = "three-2",
                    origin = Persister
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

        val pipeline = build(
            nonFlowingFetcher = fetcher::fetch,
            persisterReader = persister::read,
            persisterWriter = persister::write,
            enableCache = true
        )

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
                    origin = Cache
                ),
                Data(
                    value = "three-1",
                    origin = Persister
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
        val pipeline = build<Int, String, String>(
            nonFlowingFetcher = fetcher::fetch,
            enableCache = true
        )

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
                    origin = Cache
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
        val pipeline = build<Int, String, String>(
            nonFlowingFetcher = fetcher::fetch,
            enableCache = true
        )

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
        val fetcher = FlowingFakeFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val persister = InMemoryPersister<Int, String>()

        val pipeline = build(
            flowingFetcher = fetcher::createFlow,
            persisterReader = persister::read,
            persisterWriter = persister::write,
            enableCache = false
        )

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
                    origin = Persister
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
        val pipeline = build(
            flowingFetcher = {
                flow {
                }
            },
            flowingPersisterReader = persister::flowReader,
            persisterWriter = persister::flowWriter,
            enableCache = false
        )

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
                    origin = Persister
                )
            )
    }

    @Test
    fun diskChangeWhileNetworkIsFlowing_overwrite() = testScope.runBlockingTest {
        val persister = InMemoryPersister<Int, String>().asFlowable()
        val pipeline = build(
            flowingFetcher = {
                flow {
                    delay(10)
                    emit("three-1")
                    delay(10)
                    emit("three-2")
                }
            },
            flowingPersisterReader = persister::flowReader,
            persisterWriter = persister::flowWriter,
            enableCache = false
        )
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
                    origin = Persister
                ),
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "local-2",
                    origin = Persister
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
        val pipeline = build(
            nonFlowingFetcher = {
                throw exception
            },
            flowingPersisterReader = persister::flowReader,
            persisterWriter = persister::flowWriter,
            enableCache = false
        )
        launch {
            delay(10)
            persister.flowWriter(3, "local-1")
        }
        assertThat(pipeline.stream(StoreRequest.cached(key = 3, refresh = true)))
            .emitsExactly(
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Error(
                    error = exception,
                    origin = ResponseOrigin.Fetcher
                ),
                Data(
                    value = "local-1",
                    origin = Persister
                )
            )
        assertThat(pipeline.stream(StoreRequest.cached(key = 3, refresh = true)))
            .emitsExactly(
                Data(
                    value = "local-1",
                    origin = Persister
                ),
                Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Error(
                    error = exception,
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
            val store = build<Int, String, String>(
                nonFlowingFetcher = fetcher::fetch,
                enableCache = true
            )
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
                    origin = Cache
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
                    origin = Cache
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
            val pipeline = build(
                nonFlowingFetcher = fetcher::fetch,
                persisterReader = persister::read,
                persisterWriter = persister::write,
                enableCache = true
            )
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
                    origin = Cache
                ),
                Data(
                    value = "three-1",
                    origin = Persister
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
                    origin = Cache
                ),
                Data(
                    value = "three-1",
                    origin = Persister
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
            val pipeline = build<Int, String, String>(
                nonFlowingFetcher = fetcher::fetch,
                enableCache = true
            )
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
                    Loading<String>(origin = ResponseOrigin.Fetcher),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-1")
                )
            )
            assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
                .emitsExactly(
                    Data(origin = Cache, value = "three-1"),
                    Loading(origin = ResponseOrigin.Fetcher),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-2")
                )
            assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
                .emitsExactly(
                    Data(origin = Cache, value = "three-2"),
                    Loading(origin = ResponseOrigin.Fetcher),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-3")
                )
            testScope.advanceUntilIdle()
            assertThat(fetcher1Collected).isEqualTo(
                listOf(
                    Loading<String>(origin = ResponseOrigin.Fetcher),
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
            val pipeline = build<Int, String, String>(
                nonFlowingFetcher = fetcher::fetch,
                enableCache = true
            )
            val fetcher1Collected = mutableListOf<StoreResponse<String>>()
            val fetcher1Job = async {
                pipeline.stream(StoreRequest.cached(3, refresh = true)).collect {
                    fetcher1Collected.add(it)
                }
            }
            testScope.runCurrent()
            assertThat(fetcher1Collected).isEqualTo(
                listOf(
                    Loading<String>(origin = ResponseOrigin.Fetcher),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-1")
                )
            )
            assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
                .emitsExactly(
                    Data(origin = Cache, value = "three-1"),
                    Loading(origin = ResponseOrigin.Fetcher),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-2")
                )
            testScope.runCurrent()
            assertThat(fetcher1Collected).isEqualTo(
                listOf(
                    Loading<String>(origin = ResponseOrigin.Fetcher),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-1"),
                    Data(origin = ResponseOrigin.Fetcher, value = "three-2")
                )
            )
            fetcher1Job.cancelAndJoin()
        }

    suspend fun Store<Int, String>.get(request: StoreRequest<Int>) =
        this.stream(request).filter { it.dataOrNull() != null }.first()

    suspend fun Store<Int, String>.get(key: Int) = get(
        StoreRequest.cached(
            key = key,
            refresh = false
        )
    )

    private class FlowingFakeFetcher<Key, Output>(
        vararg val responses: Pair<Key, Output>
    ) {
        fun createFlow(key: Key) = flow {
            responses.filter {
                it.first == key
            }.forEach {
                // we delay here to avoid collapsing fetcher values, otherwise, there is a
                // possibility that consumer won't be fast enough to get both values before new
                // value overrides the previous one.
                delay(1)
                emit(it.second)
            }
        }
    }

    private fun <Key : Any, Input : Any, Output : Any> build(
        nonFlowingFetcher: (suspend (Key) -> Input)? = null,
        flowingFetcher: ((Key) -> Flow<Input>)? = null,
        persisterReader: (suspend (Key) -> Output?)? = null,
        flowingPersisterReader: ((Key) -> Flow<Output?>)? = null,
        persisterWriter: (suspend (Key, Input) -> Unit)? = null,
        persisterDelete: (suspend (Key) -> Unit)? = null,
        enableCache: Boolean
    ): Store<Key, Output> {
        check(nonFlowingFetcher != null || flowingFetcher != null) {
            "need to provide a fetcher"
        }
        check(nonFlowingFetcher == null || flowingFetcher == null) {
            "need 1 fetcher"
        }
        check(persisterReader == null || flowingPersisterReader == null) {
            "need 0 or 1 persister"
        }

        return if (nonFlowingFetcher != null) {
            StoreBuilder.from(
                Fetcher.fromNonFlowingValueFetcher(
                    nonFlowingFetcher
                )
            )
        } else {
            StoreBuilder.from(
                Fetcher.fromValueFetcher(
                    flowingFetcher!!
                )
            )
        }.scope(testScope)
            .let {
                if (enableCache) {
                    it
                } else {
                    it.disableCache()
                }
            }.let {
                @Suppress("UNCHECKED_CAST")
                when {
                    flowingPersisterReader != null -> it.persister(
                        reader = flowingPersisterReader,
                        writer = persisterWriter!!,
                        delete = persisterDelete
                    )
                    persisterReader != null -> it.nonFlowingPersister(
                        reader = persisterReader,
                        writer = persisterWriter!!,
                        delete = persisterDelete
                    )
                    else -> it
                } as StoreBuilder<Key, Output>
            }.build()
    }
}
