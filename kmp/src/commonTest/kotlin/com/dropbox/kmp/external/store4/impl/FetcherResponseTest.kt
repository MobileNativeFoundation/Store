package com.dropbox.kmp.external.store4.impl

import com.dropbox.kmp.external.store4.*
import com.dropbox.kmp.external.store4.testutil.coroutines.TestCoroutineScope
import com.dropbox.kmp.external.store4.testutil.coroutines.runBlockingTest
import com.dropbox.kmp.external.store4.testutil.emitsExactly
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@ExperimentalTime
@InternalCoroutinesApi
@ExperimentalCoroutinesApi
@FlowPreview
class FetcherResponseTest {
    private val testScope = TestCoroutineScope()

    @Test
    fun `GIVEN a Fetcher that throws an exception in invoke WHEN streaming THEN the exceptions should not be caught`() {
        val result = kotlin.runCatching {
            testScope.runBlockingTest {
                val store = StoreBuilder.from<Int, Int>(
                        Fetcher.ofResult {
                            throw RuntimeException("don't catch me")
                        }
                ).buildWithTestScope()

                val result = store.stream(StoreRequest.fresh(1)).toList()
                assertTrue { result.isEmpty() }
            }
        }
        assertTrue { result.isFailure }
        assertTrue { result.exceptionOrNull()?.message?.contains("don't catch me") ?: false }
    }

    @Test
    fun `GIVEN a Fetcher that emits Error and Data WHEN steaming THEN it can emit value after an error`() {
        val exception = RuntimeException("first error")
        testScope.runBlockingTest {
            val store = StoreBuilder.from(
                    fetcher = Fetcher.ofResultFlow { key: Int ->
                        flowOf<FetcherResult<String>>(
                                FetcherResult.Error.Exception(exception),
                                FetcherResult.Data("$key")
                        )
                    }
            ).buildWithTestScope()

            store.stream(StoreRequest.fresh(1)).emitsExactly(
                    StoreResponse.Loading(ResponseOrigin.Fetcher),
                    StoreResponse.Error.Exception(exception, ResponseOrigin.Fetcher),
                    StoreResponse.Data("1", ResponseOrigin.Fetcher)
            )
        }
    }

    // TODO - Implement multiplatform cache
//    @Test
//    fun `GIVEN transformer WHEN raw value THEN unwrapped value returned AND value is cached`() =
//            testScope.runBlockingTest {
//                val fetcher = Fetcher.ofFlow<Int, Int> { flowOf(it * it) }
//                val pipeline = StoreBuilder
//                        .from(fetcher).buildWithTestScope()
//
//                pipeline.stream(StoreRequest.cached(3, refresh = false)).emitsExactly(
//                        StoreResponse.Loading(
//                                origin = ResponseOrigin.Fetcher
//                        ),
//                        StoreResponse.Data(
//                                value = 9,
//                                origin = ResponseOrigin.Fetcher
//                        )
//                )
//
//                pipeline.stream(StoreRequest.cached(3, refresh = false)).emitsExactly(
//                        StoreResponse.Data(
//                                value = 9,
//                                origin = ResponseOrigin.Cache
//                        )
//                )
//            }

//    @Test
//    fun `GIVEN transformer WHEN error message THEN error returned to user AND error isn't cached`() =
//        testScope.runBlockingTest {
//            var count = 0
//            val fetcher = Fetcher.ofResultFlow { _: Int ->
//                flowOf(count++).map {
//                    if (it > 0) {
//                        FetcherResult.Data(it)
//                    } else {
//                        FetcherResult.Error.Message("zero")
//                    }
//                }
//            }
//            val pipeline = StoreBuilder.from(fetcher)
//                .buildWithTestScope()
//
//            assertThat(pipeline.stream(StoreRequest.fresh(3)))
//                .emitsExactly(
//                    StoreResponse.Loading(
//                        origin = ResponseOrigin.Fetcher
//                    ),
//                    StoreResponse.Error.Message(
//                        message = "zero",
//                        origin = ResponseOrigin.Fetcher
//                    )
//                )
//            assertThat(
//                pipeline.stream(StoreRequest.cached(3, refresh = false))
//            ).emitsExactly(
//                StoreResponse.Loading(
//                    origin = ResponseOrigin.Fetcher
//                ),
//                StoreResponse.Data(
//                    value = 1,
//                    origin = ResponseOrigin.Fetcher
//                )
//            )
//        }
//
//    @Test
//    fun `GIVEN transformer WHEN error exception THEN error returned to user AND error isn't cached`() =
//        testScope.runBlockingTest {
//            val e = Exception()
//            var count = 0
//            val fetcher = Fetcher.ofResultFlow { _: Int ->
//                flowOf(count++).map {
//                    if (it > 0) {
//                        FetcherResult.Data(it)
//                    } else {
//                        FetcherResult.Error.Exception(e)
//                    }
//                }
//            }
//            val pipeline = StoreBuilder
//                .from(fetcher)
//                .buildWithTestScope()
//
//            assertThat(pipeline.stream(StoreRequest.fresh(3)))
//                .emitsExactly(
//                    StoreResponse.Loading(
//                        origin = ResponseOrigin.Fetcher
//                    ),
//                    StoreResponse.Error.Exception(
//                        error = e,
//                        origin = ResponseOrigin.Fetcher
//                    )
//                )
//            assertThat(
//                pipeline.stream(StoreRequest.cached(3, refresh = false))
//            ).emitsExactly(
//                StoreResponse.Loading(
//                    origin = ResponseOrigin.Fetcher
//                ),
//                StoreResponse.Data(
//                    value = 1,
//                    origin = ResponseOrigin.Fetcher
//                )
//            )
//        }
//
//    @Test
//    fun `GIVEN exceptionsAsErrors WHEN exception thrown THEN error returned to user AND error isn't cached`() =
//        testScope.runBlockingTest {
//            var count = 0
//            val e = Exception()
//            val fetcher = Fetcher.of<Int, Int> {
//                count++
//                if (count == 1) {
//                    throw e
//                }
//                count - 1
//            }
//            val pipeline = StoreBuilder
//                .from(fetcher = fetcher)
//                .buildWithTestScope()
//
//            assertThat(pipeline.stream(StoreRequest.fresh(3)))
//                .emitsExactly(
//                    StoreResponse.Loading(
//                        origin = ResponseOrigin.Fetcher
//                    ),
//                    StoreResponse.Error.Exception(
//                        error = e,
//                        origin = ResponseOrigin.Fetcher
//                    )
//                )
//            assertThat(
//                pipeline.stream(StoreRequest.cached(3, refresh = false))
//            ).emitsExactly(
//                StoreResponse.Loading(
//                    origin = ResponseOrigin.Fetcher
//                ),
//                StoreResponse.Data(
//                    value = 1,
//                    origin = ResponseOrigin.Fetcher
//                )
//            )
//        }

    private fun <Key : Any, Output : Any> StoreBuilder<Key, Output>.buildWithTestScope() =
            scope(testScope).build()
}
