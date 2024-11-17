package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.test_utils.assertEmitsExactly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
@FlowPreview
class FetcherResponseTests {
    private val testScope = TestScope()

    @Test
    fun givenAFetcherThatThrowsAnExceptionInInvokeWhenStreamingThenTheExceptionsShouldNotBeCaught() =
        testScope.runTest {
            val store =
                StoreBuilder.from(
                    Fetcher.ofResult {
                        throw RuntimeException("don't catch me")
                    },
                ).buildWithTestScope()

            assertFailsWith<RuntimeException>(message = "don't catch me") {
                val result = store.stream(StoreReadRequest.fresh(1)).toList()
                assertEquals(0, result.size)
            }
        }

    @Test
    fun givenAFetcherThatEmitsErrorAndDataWhenSteamingThenItCanEmitValueAfterAnError() {
        val exception = RuntimeException("first error")
        testScope.runTest {
            val store =
                StoreBuilder.from(
                    fetcher =
                        Fetcher.ofResultFlow { key: Int ->
                            flowOf(
                                FetcherResult.Error.Exception(exception),
                                FetcherResult.Data("$key"),
                            )
                        },
                ).buildWithTestScope()

            assertEmitsExactly(
                store.stream(
                    StoreReadRequest.fresh(1),
                ),
                listOf(
                    StoreReadResponse.Loading(StoreReadResponseOrigin.Fetcher()),
                    StoreReadResponse.Error.Exception(exception, StoreReadResponseOrigin.Fetcher()),
                    StoreReadResponse.Data("1", StoreReadResponseOrigin.Fetcher()),
                ),
            )
        }
    }

    @Test
    fun givenTransformerWhenRawValueThenUnwrappedValueReturnedAndValueIsCached() =
        testScope.runTest {
            val fetcher = Fetcher.ofFlow<Int, Int> { flowOf(it * it) }
            val pipeline =
                StoreBuilder
                    .from(fetcher).buildWithTestScope()

            assertEmitsExactly(
                pipeline.stream(StoreReadRequest.cached(3, refresh = false)),
                listOf(
                    StoreReadResponse.Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    StoreReadResponse.Data(
                        value = 9,
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                ),
            )
            assertEmitsExactly(
                pipeline.stream(StoreReadRequest.cached(3, refresh = false)),
                listOf(
                    StoreReadResponse.Data(
                        value = 9,
                        origin = StoreReadResponseOrigin.Cache,
                    ),
                ),
            )
        }

    @Test
    fun givenTransformerWhenErrorMessageThenErrorReturnedToUserAndErrorIsNotCached() =
        testScope.runTest {
            var count = 0
            val fetcher =
                Fetcher.ofResultFlow { _: Int ->
                    flowOf(count++).map {
                        if (it > 0) {
                            FetcherResult.Data(it)
                        } else {
                            FetcherResult.Error.Message("zero")
                        }
                    }
                }
            val pipeline =
                StoreBuilder.from(fetcher)
                    .buildWithTestScope()

            assertEmitsExactly(
                pipeline.stream(StoreReadRequest.fresh(3)),
                listOf(
                    StoreReadResponse.Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    StoreReadResponse.Error.Message(
                        message = "zero",
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                ),
            )
            assertEmitsExactly(
                pipeline.stream(StoreReadRequest.cached(3, refresh = false)),
                listOf(
                    StoreReadResponse.Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    StoreReadResponse.Data(
                        value = 1,
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                ),
            )
        }

    @Test
    fun givenTransformerWhenErrorExceptionThenErrorReturnedToUserAndErrorIsNotCached() =
        testScope.runTest {
            val e = Exception()
            var count = 0
            val fetcher =
                Fetcher.ofResultFlow { _: Int ->
                    flowOf(count++).map {
                        if (it > 0) {
                            FetcherResult.Data(it)
                        } else {
                            FetcherResult.Error.Exception(e)
                        }
                    }
                }
            val pipeline =
                StoreBuilder
                    .from(fetcher)
                    .buildWithTestScope()

            assertEmitsExactly(
                pipeline.stream(StoreReadRequest.fresh(3)),
                listOf(
                    StoreReadResponse.Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    StoreReadResponse.Error.Exception(
                        error = e,
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                ),
            )
            assertEmitsExactly(
                pipeline.stream(StoreReadRequest.cached(3, refresh = false)),
                listOf(
                    StoreReadResponse.Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    StoreReadResponse.Data(
                        value = 1,
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                ),
            )
        }

    @Test
    fun givenExceptionsAsErrorsWhenExceptionThrownThenErrorReturnedToUserAndErrorIsNotCached() =
        testScope.runTest {
            var count = 0
            val e = Exception()
            val fetcher =
                Fetcher.of<Int, Int> {
                    count++
                    if (count == 1) {
                        throw e
                    }
                    count - 1
                }
            val pipeline =
                StoreBuilder
                    .from(fetcher = fetcher)
                    .buildWithTestScope()

            assertEmitsExactly(
                pipeline.stream(StoreReadRequest.fresh(3)),
                listOf(
                    StoreReadResponse.Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    StoreReadResponse.Error.Exception(
                        error = e,
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                ),
            )
            assertEmitsExactly(
                pipeline.stream(StoreReadRequest.cached(3, refresh = false)),
                listOf(
                    StoreReadResponse.Loading(
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                    StoreReadResponse.Data(
                        value = 1,
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                ),
            )
        }

    @Test
    fun givenAFetcherThatEmitsCustomErrorWhenStreamingThenCustomErrorShouldBeEmitted() =
        testScope.runTest {
            data class TestCustomError(val errorMessage: String)
            val customError = TestCustomError("Test custom error")

            val store =
                StoreBuilder.from(
                    fetcher =
                        Fetcher.ofResultFlow { _: Int ->
                            flowOf(
                                FetcherResult.Error.Custom(customError),
                            )
                        },
                ).buildWithTestScope()

            assertEmitsExactly(
                store.stream(StoreReadRequest.fresh(1)),
                listOf(
                    StoreReadResponse.Loading(origin = StoreReadResponseOrigin.Fetcher()),
                    StoreReadResponse.Error.Custom(
                        error = customError,
                        origin = StoreReadResponseOrigin.Fetcher(),
                    ),
                ),
            )
        }

    private fun <Key : Any, Output : Any> StoreBuilder<Key, Output>.buildWithTestScope() = scope(testScope).build()
}
