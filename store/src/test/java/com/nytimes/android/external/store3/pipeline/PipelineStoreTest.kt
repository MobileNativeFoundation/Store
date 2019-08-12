package com.nytimes.android.external.store3.pipeline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@ExperimentalCoroutinesApi
@FlowPreview
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

        assertThat(pipeline.get(3)).isEqualTo("three-1")
        assertThat(pipeline.get(3)).isEqualTo("three-1")
        assertThat(pipeline.fresh(3)).isEqualTo("three-2")
        assertThat(pipeline.get(3)).isEqualTo("three-2")
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

        assertThat(pipeline.get(3)).isEqualTo("three-1")
        assertThat(pipeline.get(3)).isEqualTo("three-1")
        assertThat(pipeline.fresh(3)).isEqualTo("three-2")

        assertThat(pipeline.get(3)).isEqualTo("three-2")
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

        assertThat(
            pipeline.streamCollectLimited(
                key = 3,
                limit = 1)
        ).isEqualTo(
            listOf(
                "three-1"
            )
        )
        assertThat(
            pipeline.streamCollectLimited(
                key = 3,
                limit = 2)
        ).isEqualTo(
            listOf(
                "three-1", "three-2"
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

        assertThat(
            pipeline.streamCollect(3)
        ).isEqualTo(
            listOf(
                "three-1"
            )
        )
        assertThat(
            pipeline.streamCollect(3)
        ).isEqualTo(
            listOf(
                "three-1", "three-2"
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
        assertThat(
            pipeline.get(StoreRequest.skipMemory(3, false))
        ).isEqualTo(
            "three-1"
        )
        assertThat(
            pipeline.get(StoreRequest.cached(3, false))
        ).isEqualTo(
            "three-1"
        )
        assertThat(
            pipeline.get(StoreRequest.skipMemory(3, false))
        ).isEqualTo(
            "three-2"
        )
    }

    suspend fun PipelineStore<Int, String>.get(request : StoreRequest<Int>) =
            this.stream(request).first()

    suspend fun PipelineStore<Int, String>.get(key: Int) = get(
        StoreRequest.cached(
            key = key,
            refresh = false
        )
    )

    suspend fun PipelineStore<Int, String>.streamCollect(key: Int) = stream(
        StoreRequest.cached(
            key = key,
            refresh = false
        )
    ).toList(mutableListOf())

    suspend fun PipelineStore<Int, String>.streamCollectLimited(key: Int, limit : Int) = stream(
        StoreRequest.cached(
            key = key,
            refresh = true
        )
    ).take(limit).toList(mutableListOf())

    suspend fun PipelineStore<Int, String>.fresh(key: Int) = get(
        StoreRequest.fresh(
            key = key
        )
    )
    private class FakeFetcher<Key, Output>(
        vararg val responses: Pair<Key, Output>
    ) {
        private var index = 0
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

        suspend fun read(key: Key) = data[key]
        suspend fun write(key: Key, output: Output) {
            data[key] = output
        }
    }
}