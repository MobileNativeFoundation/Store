package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.FetcherResult
import com.dropbox.android.external.store4.RealFetcher
import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.android.external.store4.testutil.assertThat
import com.dropbox.android.external.store4.exceptionsAsErrorsNonFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import java.lang.Exception

@ExperimentalCoroutinesApi
@FlowPreview
class RealFetcherTest {

    private val testScope = TestCoroutineScope()

    @Test
    fun `GIVEN transformer WHEN raw value THEN unwrapped value returned AND value is cached`() =
        testScope.runBlockingTest {
            val fetcher = RealFetcher<Int, Int, String>(
                doFetch = { flowOf(it * it) },
                doTransform = { flow -> flow.map { FetcherResult.Data("$it") } }
            )
            val pipeline = StoreBuilder
                .from(fetcher).buildWithTestScope()

            assertThat(pipeline.stream(StoreRequest.cached(3, refresh = false)))
                .emitsExactly(
                    StoreResponse.Loading(
                        origin = ResponseOrigin.Fetcher
                    ), StoreResponse.Data(
                        value = "9",
                        origin = ResponseOrigin.Fetcher
                    )
                )
            assertThat(
                pipeline.stream(StoreRequest.cached(3, refresh = false))
            ).emitsExactly(
                StoreResponse.Data(
                    value = "9",
                    origin = ResponseOrigin.Cache
                )
            )
        }

    @Test
    fun `GIVEN transformer WHEN error message THEN error returned to user AND error isn't cached`() =
        testScope.runBlockingTest {
            var count = 0
            val fetcher = RealFetcher<Int, Int, String>(
                doFetch = { flowOf(count++) },
                doTransform = {
                    it.map { i: Int ->
                        if (i > 0) {
                            FetcherResult.Data("positive($i)")
                        } else {
                            FetcherResult.Error.Message<String>("zero")
                        }
                    }
                }
            )
            val pipeline = StoreBuilder.from(fetcher)
                .buildWithTestScope()

            assertThat(pipeline.stream(StoreRequest.fresh(3)))
                .emitsExactly(
                    StoreResponse.Loading(
                        origin = ResponseOrigin.Fetcher
                    ), StoreResponse.Error.Message(
                        message = "zero",
                        origin = ResponseOrigin.Fetcher
                    )
                )
            assertThat(
                pipeline.stream(StoreRequest.cached(3, refresh = false))
            ).emitsExactly(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ), StoreResponse.Data(
                    value = "positive(1)",
                    origin = ResponseOrigin.Fetcher
                )
            )
        }

    @Test
    fun `GIVEN transformer WHEN error exception THEN error returned to user AND error isn't cached`() =
        testScope.runBlockingTest {
            val e = Exception()
            var count = 0
            val fetcher = RealFetcher<Int, Int, String>(
                doFetch = { flowOf(count++) },
                doTransform = {
                    it.map { i: Int ->
                        if (i > 0) {
                            FetcherResult.Data("$i")
                        } else {
                            FetcherResult.Error.Exception<String>(e)
                        }
                    }
                }
            )
            val pipeline = StoreBuilder
                .from(fetcher)
                .buildWithTestScope()

            assertThat(pipeline.stream(StoreRequest.fresh(3)))
                .emitsExactly(
                    StoreResponse.Loading(
                        origin = ResponseOrigin.Fetcher
                    ), StoreResponse.Error.Exception(
                        error = e,
                        origin = ResponseOrigin.Fetcher
                    )
                )
            assertThat(
                pipeline.stream(StoreRequest.cached(3, refresh = false))
            ).emitsExactly(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ), StoreResponse.Data(
                    value = "1",
                    origin = ResponseOrigin.Fetcher
                )
            )
        }

    @Test
    fun `GIVEN exceptionsAsErrors WHEN exception thrown THEN error returned to user AND error isn't cached`() =
        testScope.runBlockingTest {
            var count = 0
            val e = Exception()
            val fetcher = Fetcher.exceptionsAsErrorsNonFlow<Int, Int> {
                count++
                if (count == 1) {
                    throw e
                }
                count - 1
            }
            val pipeline = StoreBuilder
                .from(fetcher = fetcher)
                .buildWithTestScope()

            assertThat(pipeline.stream(StoreRequest.fresh(3)))
                .emitsExactly(
                    StoreResponse.Loading(
                        origin = ResponseOrigin.Fetcher
                    ), StoreResponse.Error.Exception(
                        error = e,
                        origin = ResponseOrigin.Fetcher
                    )
                )
            assertThat(
                pipeline.stream(StoreRequest.cached(3, refresh = false))
            ).emitsExactly(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ), StoreResponse.Data(
                    value = 1,
                    origin = ResponseOrigin.Fetcher
                )
            )
        }

    private fun <Key : Any, Output : Any> StoreBuilder<Key, Output>.buildWithTestScope() =
        scope(testScope).build()
}
