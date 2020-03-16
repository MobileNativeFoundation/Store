package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.FetcherResult
import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.android.external.store4.testutil.FakeFetcher
import com.dropbox.android.external.store4.testutil.assertThat
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import java.lang.Exception

class FetchTransformerStoreTest {

    private val testScope = TestCoroutineScope()

    @Test
    fun `GIVEN transformer WHEN raw value THEN unwrapped value returned AND value is cached`() =
        testScope.runBlockingTest {
            val fetcher = FakeFetcher(
                3 to 1
            )
            val pipeline = StoreBuilder
                .fromNonFlow(
                    fetcher = fetcher::fetch,
                    fetcherTransformer = { FetcherResult.Data("three-$it") }
                ).buildWithTestScope()

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
        }

    @Test
    fun `GIVEN transformer WHEN error message THEN error returned to user AND error isn't cached`() =
        testScope.runBlockingTest {
            val fetcher = FakeFetcher(
                3 to -1,
                3 to 1
            )
            val pipeline = StoreBuilder
                .fromNonFlow(
                    fetcher = fetcher::fetch,
                    fetcherTransformer = {
                        if (it > 0) {
                            FetcherResult.Data("three-$it")
                        } else {
                            FetcherResult.Error.Message<String>("negative($it)")
                        }
                    })
                .buildWithTestScope()

            assertThat(pipeline.stream(StoreRequest.fresh(3)))
                .emitsExactly(
                    StoreResponse.Loading(
                        origin = ResponseOrigin.Fetcher
                    ), StoreResponse.Error.Message(
                        message = "negative(-1)",
                        origin = ResponseOrigin.Fetcher
                    )
                )
            assertThat(
                pipeline.stream(StoreRequest.cached(3, refresh = false))
            ).emitsExactly(
                StoreResponse.Loading(
                    origin = ResponseOrigin.Fetcher
                ), StoreResponse.Data(
                    value = "three-1",
                    origin = ResponseOrigin.Fetcher
                )
            )
        }

    @Test
    fun `GIVEN transformer WHEN error exception THEN error returned to user AND error isn't cached`() =
        testScope.runBlockingTest {
            val e = Exception()
            val fetcher = FakeFetcher(
                3 to -1,
                3 to 1
            )
            val pipeline = StoreBuilder
                .fromNonFlow(
                    fetcher = fetcher::fetch,
                    fetcherTransformer = {
                        if (it > 0) {
                            FetcherResult.Data("three-$it")
                        } else {
                            FetcherResult.Error.Exception<String>(e)
                        }
                    })
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
                    value = "three-1",
                    origin = ResponseOrigin.Fetcher
                )
            )
        }

    @Test
    fun `GIVEN transformer WHEN exception thrown THEN error returned to user AND error isn't cached`() =
        testScope.runBlockingTest {
            var count = 0
            val e = Exception()
            val fetcher: suspend (Int) -> Int = {
                count++
                if (count == 1) {
                    throw e
                }
                count - 1
            }
            val pipeline = StoreBuilder
                .fromNonFlow(
                    fetcher = fetcher,
                    fetcherTransformer = { FetcherResult.Data("three-$it") })
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
                    value = "three-1",
                    origin = ResponseOrigin.Fetcher
                )
            )
        }

    private fun <Key : Any, Output : Any> StoreBuilder<Key, Output>.buildWithTestScope() =
        scope(testScope).build()
}
