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

import com.dropbox.android.external.store4.ResponseOrigin.Cache
import com.dropbox.android.external.store4.ResponseOrigin.Fetcher
import com.dropbox.android.external.store4.ResponseOrigin.Persister
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.android.external.store4.StoreResponse.Data
import com.dropbox.android.external.store4.StoreResponse.Loading
import com.dropbox.android.external.store4.fresh
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.collections.set

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
                    origin = Fetcher
                ), Data(
                    value = "three-1",
                    origin = Fetcher
                )
            )
        assertThat(
            pipeline.stream(StoreRequest.cached(3, refresh = false))
                .take(1) // TODO remove when Issue #59 is fixed.
        ).emitsExactly(
            Data(
                value = "three-1",
                origin = Cache
            )
        )
        assertThat(pipeline.stream(StoreRequest.fresh(3)))
            .emitsExactly(
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = Fetcher
                )
            )
        assertThat(
            pipeline.stream(StoreRequest.cached(3, refresh = false))
                .take(1) // TODO remove when Issue #59 is fixed.
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
                    origin = Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = Fetcher
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
                    origin = Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = Fetcher
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
                    origin = Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = Fetcher
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
                    origin = Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = Fetcher
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
                    origin = Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = Fetcher
                )
            )

        assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
            .emitsExactly(
                Data(
                    value = "three-1",
                    origin = Cache
                ),
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = Fetcher
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
                    origin = Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = Fetcher
                )
            )

        assertThat(pipeline.stream(StoreRequest.skipMemory(3, refresh = false)))
            .emitsExactly(
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = Fetcher
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
                    origin = Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = Fetcher
                )
            )
        assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
            .emitsExactly(
                Data(
                    value = "three-2",
                    origin = Persister
                ),
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = Fetcher
                )
            )
    }

    @Test
    fun diskChangeWhileNetworkIsFlowing_simple() = testScope.runBlockingTest {
        val persister = InMemoryPersister<Int, String>().asObservable()
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
                    origin = Fetcher
                ),
                Data(
                    value = "local-1",
                    origin = Persister
                )
            )
    }

    @Test
    fun diskChangeWhileNetworkIsFlowing_overwrite() = testScope.runBlockingTest {
        val persister = InMemoryPersister<Int, String>().asObservable()
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
                    origin = Fetcher
                ),
                Data(
                    value = "local-1",
                    origin = Persister
                ),
                Data(
                    value = "three-1",
                    origin = Fetcher
                ),
                Data(
                    value = "local-2",
                    origin = Persister
                ),
                Data(
                    value = "three-2",
                    origin = Fetcher
                )
            )
    }

    @Test
    fun errorTest() = testScope.runBlockingTest {
        val exception = IllegalArgumentException("wow")
        val persister = InMemoryPersister<Int, String>().asObservable()
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
                    origin = Fetcher
                ),
                StoreResponse.Error(
                    error = exception,
                    origin = Fetcher
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
                    origin = Fetcher
                ),
                StoreResponse.Error(
                    error = exception,
                    origin = Fetcher
                )
            )
    }
    data class StringWrapper(val wrapped: String)

    @Test
    fun avoidRefresh_withoutSourceOfTruth() = testScope.runBlockingTest {
        val fetcher = FakeFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val pipeline = build<Int, String, String>(
            nonFlowingFetcher = fetcher::fetch,
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
                value = "three-2",
                origin = Fetcher
            )
        )
        collection.cancelAndJoin()
    }

    @Test
    fun avoidRefresh_withSourceOfTruth() = testScope.runBlockingTest {
        val fetcher = FakeFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val persister = InMemoryPersister<Int, String>()
        val pipeline = build(
            nonFlowingFetcher = fetcher::fetch,
            persisterReader = { key -> persister.read(key)?.let {StringWrapper(it)} },
            persisterWriter = persister::write,
            enableCache = true
        )
        val firstFetch = pipeline.fresh(3)
        assertThat(firstFetch).isEqualTo(StringWrapper("three-1"))
        val secondCollect = mutableListOf<StoreResponse<StringWrapper>>()
        val collection = launch {
            pipeline.stream(StoreRequest.cached(3, refresh = false)).collect {
                secondCollect.add(it)
            }
        }
        testScope.runCurrent()
        assertThat(secondCollect).containsExactly(
            Data(
                value = StringWrapper("three-1"),
                origin = Cache
            ),
            Data(
                value = StringWrapper("three-1"),
                origin = Persister
            )
        )
        // trigger another fetch from network
        val secondFetch = pipeline.fresh(3)
        assertThat(secondFetch).isEqualTo(StringWrapper("three-2"))
        testScope.runCurrent()
        // make sure cached also received it
        assertThat(secondCollect).containsExactly(
            Data(
                value = StringWrapper("three-1"),
                origin = Cache
            ),
            Data(
                value = StringWrapper("three-1"),
                origin = Persister
            ),
            Data(
                value = StringWrapper("three-2"),
                origin = Fetcher
            )
        )
        collection.cancelAndJoin()
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

    class InMemoryPersister<Key, Output> {
        private val data = mutableMapOf<Key, Output>()

        @Suppress("RedundantSuspendModifier") // for function reference
        suspend fun read(key: Key) = data[key]

        @Suppress("RedundantSuspendModifier") // for function reference
        suspend fun write(key: Key, output: Output) {
            data[key] = output
        }

        suspend fun asObservable() = SimplePersisterAsFlowable(
            reader = this::read,
            writer = this::write
        )
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
            StoreBuilder.fromNonFlow(
                nonFlowingFetcher
            )
        } else {
            StoreBuilder.from(
                flowingFetcher!!
            )
        }.scope(testScope)
            .let {
                if (enableCache) {
                    it
                } else {
                    it.disableCache()
                }
            }.let {
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
