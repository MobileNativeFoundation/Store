package com.dropbox.kmp.external.store4.impl

import com.dropbox.kmp.external.store4.Fetcher
import com.dropbox.kmp.external.store4.FetcherResult
import com.dropbox.kmp.external.store4.testutil.coroutines.TestCoroutineScope
import com.dropbox.kmp.external.store4.testutil.coroutines.runBlockingTest
import com.dropbox.kmp.external.store4.testutil.emitsExactlyAndCompletes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.time.ExperimentalTime

@ExperimentalTime
@InternalCoroutinesApi
@ExperimentalCoroutinesApi
@FlowPreview
class ValueFetcherTest {

    private val testScope = TestCoroutineScope()

    @Test
    fun `GIVEN valueFetcher WHEN invoke THEN result is wrapped`() =
        testScope.runBlockingTest {
            val fetcher = Fetcher.ofFlow<Int, Int> { flowOf(it * it) }

            fetcher(3).emitsExactlyAndCompletes(FetcherResult.Data(value = 9))
        }

    @Test
    fun `GIVEN valueFetcher WHEN exception in flow THEN exception returned as result`() =
        testScope.runBlockingTest {
            val e = Exception()
            val fetcher = Fetcher.ofFlow<Int, Int> {
                flow {
                    throw e
                }
            }
            fetcher(3).emitsExactlyAndCompletes(FetcherResult.Error.Exception(e))
        }

    @Test
    fun `GIVEN nonFlowValueFetcher WHEN invoke THEN result is wrapped`() =
        testScope.runBlockingTest {
            val fetcher = Fetcher.of<Int, Int> { it * it }

            fetcher(3).emitsExactlyAndCompletes(FetcherResult.Data(value = 9))
        }

    @Test
    fun `GIVEN nonFlowValueFetcher WHEN exception in flow THEN exception returned as result`() =
        testScope.runBlockingTest {
            val e = Exception()
            val fetcher = Fetcher.of<Int, Int> {
                throw e
            }
            fetcher(3).emitsExactlyAndCompletes(FetcherResult.Error.Exception(e))
        }
}
