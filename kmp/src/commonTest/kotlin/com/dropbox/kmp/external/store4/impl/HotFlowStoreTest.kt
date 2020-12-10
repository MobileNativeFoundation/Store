package com.dropbox.kmp.external.store4.impl

import com.dropbox.kmp.external.store4.*
import com.dropbox.kmp.external.store4.testutil.coroutines.TestCoroutineScope
import com.dropbox.kmp.external.store4.testutil.coroutines.runBlockingTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals

@InternalCoroutinesApi
@ExperimentalCoroutinesApi
@FlowPreview
class HotFlowStoreTest {
    private val testScope = TestCoroutineScope()

    // Todo - Implement multiplatform cache
//    @Test
//    fun `GIVEN a hot fetcher WHEN two cached and one fresh call THEN fetcher is only called twice`() =
//        testScope.runBlockingTest {
//            val fetcher = FakeFlowFetcher(
//                3 to "three-1",
//                3 to "three-2"
//            )
//            val pipeline = StoreBuilder
//                .from(fetcher)
//                .scope(testScope)
//                .build()
//
//            assertThat(pipeline.stream(StoreRequest.cached(3, refresh = false)))
//                .emitsExactly(
//                    StoreResponse.Loading(
//                        origin = ResponseOrigin.Fetcher
//                    ),
//                    StoreResponse.Data(
//                        value = "three-1",
//                        origin = ResponseOrigin.Fetcher
//                    )
//                )
//            assertThat(
//                pipeline.stream(StoreRequest.cached(3, refresh = false))
//            ).emitsExactly(
//                StoreResponse.Data(
//                    value = "three-1",
//                    origin = ResponseOrigin.Cache
//                )
//            )
//
//            assertThat(pipeline.stream(StoreRequest.fresh(3)))
//                .emitsExactly(
//                    StoreResponse.Loading(
//                        origin = ResponseOrigin.Fetcher
//                    ),
//                    StoreResponse.Data(
//                        value = "three-2",
//                        origin = ResponseOrigin.Fetcher
//                    )
//                )
//        }
}

class FakeFlowFetcher<Key : Any, Output : Any>(
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
