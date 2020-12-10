/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dropbox.kmp.external.store4

import com.dropbox.kmp.external.store4.StoreResponse.Data
import com.dropbox.kmp.external.store4.impl.FetcherController
import com.dropbox.kmp.external.store4.testutil.coroutines.TestCoroutineScope
import com.dropbox.kmp.external.store4.testutil.coroutines.runBlockingTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlin.test.Test
import kotlin.test.assertEquals

@InternalCoroutinesApi
@ExperimentalCoroutinesApi
@FlowPreview
class FetcherControllerTest {

    private val testScope = TestCoroutineScope()

    @Test
    fun simple() = testScope.runBlockingTest {
        val fetcherController = FetcherController<Int, Int, Int>(
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
        assertEquals(Data(value = 9, origin = ResponseOrigin.Fetcher), received)
        assertEquals(0, fetcherController.fetcherSize())
    }

    @Test
    fun concurrent() = testScope.runBlockingTest {
        var createdCnt = 0
        val fetcherController = FetcherController<Int, Int, Int>(
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
            assertEquals(Data(value = 9, origin = ResponseOrigin.Fetcher), it.await())
        }
        assertEquals(0, fetcherController.fetcherSize())
        assertEquals(1, createdCnt)
    }
}
