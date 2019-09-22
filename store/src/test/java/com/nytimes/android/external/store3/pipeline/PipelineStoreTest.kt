package com.nytimes.android.external.store3.pipeline

import com.nytimes.android.external.store3.pipeline.ResponseOrigin.Cache
import com.nytimes.android.external.store3.pipeline.ResponseOrigin.Fetcher
import com.nytimes.android.external.store3.pipeline.ResponseOrigin.Persister
import com.nytimes.android.external.store3.pipeline.StoreResponse.Data
import com.nytimes.android.external.store3.pipeline.StoreResponse.Loading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class PipelineStoreTest {
    private val testScope = TestCoroutineScope()

    @Test
    fun getAndFresh() = testScope.runBlockingTest {
        val fetcher = FakeFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val pipeline = beginNonFlowingPipeline(fetcher::fetch)
            .withCache()
        pipeline.stream(StoreRequest.cached(3, refresh = false))
            .assertCompleteStream(
                Loading(
                    origin = Fetcher
                ), Data(
                    value = "three-1",
                    origin = Fetcher
                )
            )
        pipeline.stream(StoreRequest.cached(3, refresh = false))
            .assertCompleteStream(
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
    fun getAndFresh_withPersister() = runBlocking<Unit> {
        val fetcher = FakeFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val persister = InMemoryPersister<Int, String>()
        val pipeline = beginNonFlowingPipeline(fetcher::fetch)
            .withNonFlowPersister(
                reader = persister::read,
                writer = persister::write
            )
            .withCache()
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

        val pipeline = beginNonFlowingPipeline(fetcher::fetch)
            .withNonFlowPersister(
                reader = persister::read,
                writer = persister::write
            )
            .withCache()

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
        val pipeline = beginNonFlowingPipeline(fetcher::fetch)
            .withCache()

        pipeline.stream(StoreRequest.cached(3, refresh = true))
            .assertCompleteStream(
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = Fetcher
                )
            )

        pipeline.stream(StoreRequest.cached(3, refresh = true))
            .assertCompleteStream(
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
        val pipeline = beginNonFlowingPipeline(fetcher::fetch)
            .withCache()

        pipeline.stream(StoreRequest.skipMemory(3, refresh = false))
            .assertCompleteStream(
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = Fetcher
                )
            )

        pipeline.stream(StoreRequest.skipMemory(3, refresh = false))
            .assertCompleteStream(
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

        val pipeline = beginPipeline(fetcher::createFlow)
            .withNonFlowPersister(
                reader = persister::read,
                writer = persister::write
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

        pipeline.stream(StoreRequest.cached(3, refresh = true)).assertItems(
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
        val pipeline = beginPipeline<Int, String> {
            flow {
                // never emit
            }
        }.withPersister(
            reader = persister::flowReader,
            writer = persister::flowWriter
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
                    origin = Persister
                )
            )
    }

    @Test
    fun diskChangeWhileNetworkIsFlowing_overwrite() = testScope.runBlockingTest {
        val persister = InMemoryPersister<Int, String>().asObservable()
        val pipeline = beginPipeline<Int, String> {
            flow {
                delay(10)
                emit("three-1")
                delay(10)
                emit("three-2")
            }
        }.withPersister(
            reader = persister::flowReader,
            writer = persister::flowWriter
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
        val pipeline = beginNonFlowingPipeline<Int, String> { key: Int ->
            throw exception
        }.withPersister(
            reader = persister::flowReader,
            writer = persister::flowWriter
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
                    origin = Persister
                )
            )
        pipeline.stream(StoreRequest.cached(key = 3, refresh = true))
            .assertItems(
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

    suspend fun PipelineStore<Int, String>.get(request: StoreRequest<Int>) =
        this.stream(request).filter { it.dataOrNull() != null }.first()

    suspend fun PipelineStore<Int, String>.get(key: Int) = get(
        StoreRequest.cached(
            key = key,
            refresh = false
        )
    )

    suspend fun PipelineStore<Int, String>.fresh(key: Int) = get(
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
                emit(it.second)
                delay(1)
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

    private class InMemoryPersister<Key, Output> {
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

    /**
     * Takes all elements from the stream and asserts them.
     * Use this if test does not have an infinite flow (e.g. no persister or no infinite fetcher)
     */
    private suspend fun <T> Flow<T>.assertCompleteStream(vararg expected: T) {
        assertThat(this.toList())
            .isEqualTo(expected.toList())
    }
}