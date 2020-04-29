package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.FetcherResult
import com.dropbox.android.external.store4.nonFlowValueFetcher
import com.dropbox.android.external.store4.testutil.assertThat
import com.dropbox.android.external.store4.valueFetcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test

@ExperimentalCoroutinesApi
@FlowPreview
class ValueFetcherTest {

    private val testScope = TestCoroutineScope()

    @Test
    fun `GIVEN valueFetcher WHEN invoke THEN result is wrapped`() =
        testScope.runBlockingTest {
            val fetcher = valueFetcher<Int, Int> { flowOf(it * it) }

            assertThat(fetcher(3))
                .emitsExactly(FetcherResult.Data(value = 9))
        }

    @Test
    fun `GIVEN valueFetcher WHEN exception in flow THEN exception returned as result`() =
        testScope.runBlockingTest {
            val e = Exception()
            val fetcher = valueFetcher<Int, Int> {
                flow {
                    throw e
                }
            }
            assertThat(fetcher(3))
                .emitsExactly(FetcherResult.Error.Exception(e))
        }

    @Test
    fun `GIVEN nonFlowValueFetcher WHEN invoke THEN result is wrapped`() =
        testScope.runBlockingTest {
            val fetcher = nonFlowValueFetcher<Int, Int> { it * it }

            assertThat(fetcher(3))
                .emitsExactly(FetcherResult.Data(value = 9))
        }

    @Test
    fun `GIVEN nonFlowValueFetcher WHEN exception in flow THEN exception returned as result`() =
        testScope.runBlockingTest {
            val e = Exception()
            val fetcher = nonFlowValueFetcher<Int, Int> {
                    throw e
            }
            assertThat(fetcher(3))
                .emitsExactly(FetcherResult.Error.Exception(e))
        }
}
