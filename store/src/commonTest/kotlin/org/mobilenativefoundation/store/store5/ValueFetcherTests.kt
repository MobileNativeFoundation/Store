package org.mobilenativefoundation.store.store5

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@ExperimentalCoroutinesApi
@FlowPreview
class ValueFetcherTests {
    private val testScope = TestScope()

    @Test
    fun givenValueFetcherWhenInvokeThenResultIsWrapped() =
        testScope.runTest {
            val fetcher = Fetcher.ofFlow<Int, Int> { flowOf(it * it) }

            fetcher(3).test {
                assertEquals(FetcherResult.Data(value = 9), awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun givenValueFetcherWhenExceptionInFlowThenExceptionReturnedAsResult() =
        testScope.runTest {
            val e = Exception()
            val fetcher =
                Fetcher.ofFlow<Int, Int> {
                    flow {
                        throw e
                    }
                }
            fetcher(3).test {
                assertEquals(FetcherResult.Error.Exception(e), awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun givenNonFlowValueFetcherWhenInvokeThenResultIsWrapped() =
        testScope.runTest {
            val fetcher = Fetcher.of<Int, Int> { it * it }

            fetcher(3).test {
                assertEquals(FetcherResult.Data(value = 9), awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun givenNonFlowValueFetcherWhenExceptionInFlowThenExceptionReturnedAsResult() =
        testScope.runTest {
            val e = Exception()
            val fetcher =
                Fetcher.of<Int, Int> {
                    throw e
                }
            fetcher(3).test {
                assertEquals(FetcherResult.Error.Exception(e), awaitItem())
                awaitComplete()
            }
        }
}
