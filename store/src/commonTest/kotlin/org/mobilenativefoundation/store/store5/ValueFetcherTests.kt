package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.util.assertEmitsExactly
import kotlin.test.Test

@ExperimentalCoroutinesApi
@FlowPreview
class ValueFetcherTests {

    private val testScope = TestScope()

    @Test
    fun givenValueFetcherWhenInvokeThenResultIsWrapped() = testScope.runTest {
        val fetcher = Fetcher.ofFlow<Int, Int> { flowOf(it * it) }
        assertEmitsExactly(fetcher(3), listOf(FetcherResult.Data(value = 9)))
    }

    @Test
    fun givenValueFetcherWhenExceptionInFlowThenExceptionReturnedAsResult() = testScope.runTest {
        val e = Exception()
        val fetcher = Fetcher.ofFlow<Int, Int> {
            flow {
                throw e
            }
        }
        assertEmitsExactly(
            fetcher(3),
            listOf(FetcherResult.Error.Exception(e))
        )
    }

    @Test
    fun givenNonFlowValueFetcherWhenInvokeThenResultIsWrapped() = testScope.runTest {
        val fetcher = Fetcher.of<Int, Int> { it * it }

        assertEmitsExactly(
            fetcher(3),
            listOf(FetcherResult.Data(value = 9))
        )
    }

    @Test
    fun givenNonFlowValueFetcherWhenExceptionInFlowThenExceptionReturnedAsResult() = testScope.runTest {
        val e = Exception()
        val fetcher = Fetcher.of<Int, Int> {
            throw e
        }
        assertEmitsExactly(fetcher(3), listOf(FetcherResult.Error.Exception(e)))
    }
}
