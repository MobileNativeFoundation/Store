package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.android.external.store4.testutil.assertThat
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@ExperimentalCoroutinesApi
@FlowPreview
@RunWith(JUnit4::class)
class HotFlowStoreTest {
    private val testScope = TestCoroutineScope()
    @Test
    fun `GIVEN a hot fetcher WHEN two cached and one fresh call THEN fetcher is only called twice`() =
        testScope.runBlockingTest {
            val fetcher = FakeFlowFetcher(
                3 to "three-1",
                3 to "three-2"
            )
            val pipeline = StoreBuilder.from<Int, String> { fetcher.fetch(it) }
                .scope(testScope)
                .build()

            assertThat(pipeline.stream(StoreRequest.cached(3, refresh = false)))
                .emitsExactly(
                    StoreResponse.Loading(
                        origin = ResponseOrigin.Fetcher
                    ), StoreResponse.Data(
                        value = "three-1",
                        origin = ResponseOrigin.Fetcher
                    )
                )
            assertThat(
                pipeline.stream(StoreRequest.cached(3, refresh = false))
            ).emitsExactly(
                StoreResponse.Data(
                    value = "three-1",
                    origin = ResponseOrigin.Cache
                )
            )

            assertThat(pipeline.stream(StoreRequest.fresh(3)))
                .emitsExactly(
                    StoreResponse.Loading(
                        origin = ResponseOrigin.Fetcher
                    ),
                    StoreResponse.Data(
                        value = "three-2",
                        origin = ResponseOrigin.Fetcher
                    )
                )
        }
}

class FakeFlowFetcher<Key, Output>(
    vararg val responses: Pair<Key, Output>
) {
    private var index = 0
    @Suppress("RedundantSuspendModifier") // needed for function reference
    fun fetch(key: Key): Flow<Output> {
        if (index >= responses.size) {
            throw AssertionError("unexpected fetch request")
        }
        val pair = responses[index++]
        assertThat(pair.first).isEqualTo(key)
        return flowOf(pair.second)
    }
}
