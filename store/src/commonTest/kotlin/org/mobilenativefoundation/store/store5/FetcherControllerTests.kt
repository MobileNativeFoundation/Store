package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.StoreReadResponse.Data
import org.mobilenativefoundation.store.store5.impl.FetcherController
import kotlin.test.Test
import kotlin.test.assertEquals

@ExperimentalCoroutinesApi
@FlowPreview
class FetcherControllerTests {
    private val testScope = TestScope()

    @Test
    fun simple() = testScope.runTest {
        val fetcherController = FetcherController<Int, Int, Int, Int>(
            scope = testScope,
            realFetcher = Fetcher.ofResultFlow { key: Int ->
                flow {
                    emit(FetcherResult.Data(key * key) as FetcherResult<Int>)
                }
            },
            sourceOfTruth = null
        )
        val fetcher = fetcherController.getFetcher(3)
        assertEquals(0, fetcherController.fetcherSize())
        val received = fetcher.onEach {
            assertEquals(1, fetcherController.fetcherSize())
        }.first()
        assertEquals(
            Data(
                value = 9,
                origin = StoreReadResponseOrigin.Fetcher
            ),
            received
        )
        assertEquals(0, fetcherController.fetcherSize())
    }

    @Test
    fun concurrent() = testScope.runTest {
        var createdCnt = 0
        val fetcherController = FetcherController<Int, Int, Int, Int>(
            scope = testScope,
            realFetcher = Fetcher.ofResultFlow { key: Int ->
                createdCnt++
                flow {
                    // make sure it takes time, otherwise, we may not share
                    delay(1)
                    emit(FetcherResult.Data(key * key) as FetcherResult<Int>)
                }
            },
            sourceOfTruth = null
        )
        val fetcherCount = 20
        fun createFetcher() = async {
            fetcherController.getFetcher(3)
                .onEach {
                    assertEquals(1, fetcherController.fetcherSize())
                }.first()
        }

        val fetchers = (0 until fetcherCount).map {
            createFetcher()
        }
        fetchers.forEach {
            assertEquals(
                Data(
                    value = 9,
                    origin = StoreReadResponseOrigin.Fetcher
                ),
                it.await()
            )
        }
        assertEquals(0, fetcherController.fetcherSize())
        assertEquals(1, createdCnt)
    }

    @Test
    fun concurrent_when_cancelled() = testScope.runTest {
        var createdCnt = 0
        val job = SupervisorJob()
        val scope = TestScope(StandardTestDispatcher() + job)
        val fetcherController = FetcherController<Int, Int, Int, Int>(
            scope = scope,
            realFetcher = Fetcher.ofResultFlow { key: Int ->
                createdCnt++
                flow {
                    // make sure it takes time, otherwise, we may not share
                    advanceUntilIdle()
                    emit(FetcherResult.Data(key * key) as FetcherResult<Int>)
                }
            },
            sourceOfTruth = null
        )
        val fetcherCount = 20

        fun createFetcher() = scope.launch {
            fetcherController.getFetcher(3)
                .onEach {
                    assertEquals(1, fetcherController.fetcherSize())
                }.first()
        }

        (0 until fetcherCount).map {
            createFetcher()
        }
        scope.advanceUntilIdle()
        job.cancelChildren()
        scope.advanceUntilIdle()
        assertEquals(0, fetcherController.fetcherSize())
        assertEquals(1, createdCnt)
    }
}
