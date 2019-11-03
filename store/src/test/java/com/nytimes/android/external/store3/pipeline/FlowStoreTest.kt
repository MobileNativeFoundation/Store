package com.nytimes.android.external.store3.pipeline

import com.nytimes.android.external.store3.TestStoreType
import com.nytimes.android.external.store4.*
import com.nytimes.android.external.store4.ResponseOrigin.Cache
import com.nytimes.android.external.store4.ResponseOrigin.Fetcher
import com.nytimes.android.external.store4.StoreResponse.Data
import com.nytimes.android.external.store4.StoreResponse.Loading
import com.nytimes.android.external.store4.impl.SimplePersisterAsFlowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@ExperimentalCoroutinesApi
@RunWith(Parameterized::class)
class FlowStoreTest(
    private val storeType: TestStoreType
) {
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
        pipeline.stream(StoreRequest.cached(3, refresh = false))
            .assertItems(
                Loading(
                    origin = Fetcher
                ), Data(
                    value = "three-1",
                    origin = Fetcher
                )
            )
        pipeline.stream(StoreRequest.cached(3, refresh = false))
            .assertItems(
                Data(
                    value = "three-1",
                    origin = Cache
                )
            )
        pipeline.stream(StoreRequest.fresh(3))
            .assertItems(
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = Fetcher
                )
            )
        pipeline.stream(StoreRequest.cached(3, refresh = false))
            .assertItems(
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
        pipeline.stream(StoreRequest.cached(3, refresh = false))
            .assertItems(
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = Fetcher
                )
            )
        pipeline.stream(StoreRequest.cached(3, refresh = false))
            .assertItems(
                Data(
                    value = "three-1",
                    origin = Cache
                )
            )
        pipeline.stream(StoreRequest.fresh(3))
            .assertItems(
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = Fetcher
                )
            )
        pipeline.stream(StoreRequest.cached(3, refresh = false))
            .assertItems(
                Data(
                    value = "three-2",
                    origin = Cache
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

        pipeline.stream(StoreRequest.cached(3, refresh = true))
            .assertItems(
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = Fetcher
                )
            )

        pipeline.stream(StoreRequest.cached(3, refresh = true))
            .assertItems(
                Data(
                    value = "three-1",
                    origin = Cache
                ),
                Data(
                    value = "three-1",
                    origin = ResponseOrigin.Persister
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

        pipeline.stream(StoreRequest.cached(3, refresh = true))
            .assertItems(
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = Fetcher
                )
            )

        pipeline.stream(StoreRequest.cached(3, refresh = true))
            .assertItems(
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

        pipeline.stream(StoreRequest.skipMemory(3, refresh = false))
            .assertItems(
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = Fetcher
                )
            )

        pipeline.stream(StoreRequest.skipMemory(3, refresh = false))
            .assertItems(
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

        pipeline.stream(StoreRequest.fresh(3))
            .assertItems(
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
        pipeline.stream(StoreRequest.cached(3, refresh = true))
            .assertItems(
                Data(
                    value = "three-2",
                    origin = ResponseOrigin.Persister
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
        pipeline
            .stream(StoreRequest.cached(3, refresh = true))
            .assertItems(
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "local-1",
                    origin = ResponseOrigin.Persister
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
        pipeline
            .stream(StoreRequest.cached(3, refresh = true))
            .assertItems(
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "local-1",
                    origin = ResponseOrigin.Persister
                ),
                Data(
                    value = "three-1",
                    origin = Fetcher
                ),
                Data(
                    value = "local-2",
                    origin = ResponseOrigin.Persister
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
        pipeline.stream(StoreRequest.cached(key = 3, refresh = true))
            .assertItems(
                Loading(
                    origin = Fetcher
                ),
                StoreResponse.Error(
                    error = exception,
                    origin = Fetcher
                ),
                Data(
                    value = "local-1",
                    origin = ResponseOrigin.Persister
                )
            )
        pipeline.stream(StoreRequest.cached(key = 3, refresh = true))
            .assertItems(
                Data(
                    value = "local-1",
                    origin = ResponseOrigin.Persister
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

    suspend fun Store<Int, String>.get(request: StoreRequest<Int>) =
        this.stream(request).filter { it.dataOrNull() != null }.first()

    suspend fun Store<Int, String>.get(key: Int) = get(
        StoreRequest.cached(
            key = key,
            refresh = false
        )
    )

    suspend fun Store<Int, String>.fresh(key: Int) = get(
        StoreRequest.fresh(
            key = key
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

    private class FakeFetcher<Key, Output>(
        vararg val responses: Pair<Key, Output>
    ) {
        private var index = 0
        @Suppress("RedundantSuspendModifier") // needed for function reference
        suspend fun fetch(key: Key): Output {
            if (index >= responses.size) {
                throw AssertionError("unexpected fetch request")
            }
            val pair = responses[index++]
            assertThat(pair.first).isEqualTo(key)
            return pair.second
        }
    }

    class InMemoryPersister<Key, Output> {
        private val data = mutableMapOf<Key, Output>()

        @Suppress("RedundantSuspendModifier")// for function reference
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

    /**
     * Asserts only the [expected] items by just taking that many from the stream
     *
     * Use this when Pipeline has an infinite part (e.g. Persister or a never ending fetcher)
     */
    private suspend fun <T> Flow<T>.assertItems(vararg expected: T) {
        assertThat(this.take(expected.size).toList())
            .isEqualTo(expected.toList())
    }

    private fun <Key, Input, Output> build(
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
                FlowStoreBuilder.fromNonFlow(
                    nonFlowingFetcher
                )
            } else {
                FlowStoreBuilder.from<Key, Input, Output>(
                    flowingFetcher!!
                )
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
                }
            }.let {
                if (enableCache) {
                    it
                } else {
                    it.disableCache()
                }
            }.scope(testScope)
                .build()

    }


    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = listOf(TestStoreType.FlowStore)
    }
}
