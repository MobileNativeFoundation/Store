package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.util.assertEmitsExactly
import kotlin.test.Test
import kotlin.test.assertEquals

@ExperimentalCoroutinesApi
@FlowPreview
class HotFlowStoreTests {
    private val testScope = TestScope()

    @Test
    fun givenAHotFetcherWhenTwoCachedAndOneFreshCallThenFetcherIsOnlyCalledTwice() = testScope.runTest {
        val fetcher = FakeFlowFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val pipeline = StoreBuilder
            .from(fetcher)
            .scope(testScope)
            .build()

        assertEmitsExactly(
            pipeline.stream(StoreRequest.cached(3, refresh = false)),
            listOf(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                    value = "three-1",
                    origin = ResponseOrigin.Fetcher
                )
            )
        )
        assertEmitsExactly(
            pipeline.stream(
                StoreRequest.cached(3, refresh = false)
            ),
            listOf(
                StoreResponse.Data(
                    value = "three-1",
                    origin = ResponseOrigin.Cache
                )
            )
        )

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
    }
}

private class FakeFlowFetcher<Key : Any, Output : Any>(
    vararg val responses: Pair<Key, Output>
) : Fetcher<Key, Output> {
    private var index = 0

    override fun invoke(key: Key): Flow<FetcherResult<Output>> {
        if (index >= responses.size) {
            throw AssertionError("unexpected fetch request")
        }
        val pair = responses[index++]
        assertEquals(key, pair.first)
        return flowOf(FetcherResult.Data(pair.second))
    }
}
