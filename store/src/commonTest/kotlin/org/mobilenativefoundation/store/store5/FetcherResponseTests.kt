package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.util.assertEmitsExactly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
@FlowPreview
class FetcherResponseTests {
    private val testScope = TestScope()

    @Test
    fun givenAFetcherThatThrowsAnExceptionInInvokeWhenStreamingThenTheExceptionsShouldNotBeCaught() = testScope.runTest {
        val store = StoreBuilder.from<Int, Int>(
            Fetcher.ofResult {
                throw RuntimeException("don't catch me")
            }
        ).buildWithTestScope()

        assertFailsWith<RuntimeException>(message = "don't catch me") {
            val result = store.stream(StoreRequest.fresh(1)).toList()
            assertEquals(0, result.size)
        }
    }

    @Test
    fun givenAFetcherThatEmitsErrorAndDataWhenSteamingThenItCanEmitValueAfterAnError() {
        val exception = RuntimeException("first error")
        testScope.runTest {
            val store = StoreBuilder.from(
                fetcher = Fetcher.ofResultFlow { key: Int ->
                    flowOf<FetcherResult<String>>(
                        FetcherResult.Error.Exception(exception),
                        FetcherResult.Data("$key")
                    )
                }
            ).buildWithTestScope()

            assertEmitsExactly(
                store.stream(
                    StoreRequest.fresh(1)
                ),
                listOf(
                    StoreResponse.Loading(ResponseOrigin.Fetcher),
                    StoreResponse.Error.Exception(exception, ResponseOrigin.Fetcher),
                    StoreResponse.Data("1", ResponseOrigin.Fetcher)
                )
            )
        }
    }

    @Test
    fun givenTransformerWhenRawValueThenUnwrappedValueReturnedAndValueIsCached() = testScope.runTest {
        val fetcher = Fetcher.ofFlow<Int, Int> { flowOf(it * it) }
        val pipeline = StoreBuilder
            .from(fetcher).buildWithTestScope()

        assertEmitsExactly(
            pipeline.stream(StoreRequest.cached(3, refresh = false)),
            listOf(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                    value = 9,
                    origin = ResponseOrigin.Fetcher
                )
            )
        )
        assertEmitsExactly(
            pipeline.stream(StoreRequest.cached(3, refresh = false)),
            listOf(
                StoreResponse.Data(
                    value = 9,
                    origin = ResponseOrigin.Cache
                )
            )
        )
    }

    @Test
    fun givenTransformerWhenErrorMessageThenErrorReturnedToUserAndErrorIsNotCached() = testScope.runTest {
        var count = 0
        val fetcher = Fetcher.ofResultFlow { _: Int ->
            flowOf(count++).map {
                if (it > 0) {
                    FetcherResult.Data(it)
                } else {
                    FetcherResult.Error.Message("zero")
                }
            }
        }
        val pipeline = StoreBuilder.from(fetcher)
            .buildWithTestScope()

        assertEmitsExactly(
            pipeline.stream(StoreRequest.fresh(3)),
            listOf(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Error.Message(
                    message = "zero",
                    origin = ResponseOrigin.Fetcher
                )
            )
        )
        assertEmitsExactly(
            pipeline.stream(StoreRequest.cached(3, refresh = false)),
            listOf(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                    value = 1,
                    origin = ResponseOrigin.Fetcher
                )
            )
        )
    }

    @Test
    fun givenTransformerWhenErrorExceptionThenErrorReturnedToUserAndErrorIsNotCached() = testScope.runTest {
        val e = Exception()
        var count = 0
        val fetcher = Fetcher.ofResultFlow { _: Int ->
            flowOf(count++).map {
                if (it > 0) {
                    FetcherResult.Data(it)
                } else {
                    FetcherResult.Error.Exception(e)
                }
            }
        }
        val pipeline = StoreBuilder
            .from(fetcher)
            .buildWithTestScope()

        assertEmitsExactly(
            pipeline.stream(StoreRequest.fresh(3)),
            listOf(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Error.Exception(
                    error = e,
                    origin = ResponseOrigin.Fetcher
                )
            )
        )
        assertEmitsExactly(
            pipeline.stream(StoreRequest.cached(3, refresh = false)),
            listOf(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                    value = 1,
                    origin = ResponseOrigin.Fetcher
                )
            )
        )
    }

    @Test
    fun givenExceptionsAsErrorsWhenExceptionThrownThenErrorReturnedToUserAndErrorIsNotCached() = testScope.runTest {
        var count = 0
        val e = Exception()
        val fetcher = Fetcher.of<Int, Int> {
            count++
            if (count == 1) {
                throw e
            }
            count - 1
        }
        val pipeline = StoreBuilder
            .from(fetcher = fetcher)
            .buildWithTestScope()

        assertEmitsExactly(
            pipeline.stream(StoreRequest.fresh(3)),
            listOf(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Error.Exception(
                    error = e,
                    origin = ResponseOrigin.Fetcher
                )
            )
        )
        assertEmitsExactly(
            pipeline.stream(StoreRequest.cached(3, refresh = false)),
            listOf(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                    value = 1,
                    origin = ResponseOrigin.Fetcher
                )
            )
        )
    }

    private fun <Key : Any, Output : Any> StoreBuilder<Key, Output>.buildWithTestScope() =
        scope(testScope).build()
}
