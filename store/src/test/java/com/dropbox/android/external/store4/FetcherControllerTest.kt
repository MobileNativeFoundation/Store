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
package com.dropbox.android.external.store4

import com.dropbox.android.external.store4.ResponseOrigin.Fetcher
import com.dropbox.android.external.store4.StoreResponse.Data
import com.dropbox.android.external.store4.impl.FetcherController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@ExperimentalStdlibApi
@ExperimentalCoroutinesApi
@FlowPreview
@RunWith(JUnit4::class)
class FetcherControllerTest {
    private val testScope = TestCoroutineScope()
    @Test
    fun simple() = testScope.runBlockingTest {
        val fetcherController = FetcherController<Int, Int, Int>(
            scope = testScope,
            realFetcher = { key: Int ->
                flow {
                    emit(FetcherResult.Data(key * key) as FetcherResult<Int>)
                }
            },
            sourceOfTruth = null
        )
        val fetcher = fetcherController.getFetcher(3)
        assertThat(fetcherController.fetcherSize()).isEqualTo(0)
        val received = fetcher.onEach {
            assertThat(fetcherController.fetcherSize()).isEqualTo(1)
        }.first()
        assertThat(received).isEqualTo(
            Data(
                value = 9,
                origin = Fetcher
            )
        )
        assertThat(fetcherController.fetcherSize()).isEqualTo(0)
    }

    @Test
    fun concurrent() = testScope.runBlockingTest {
        var createdCnt = 0
        val fetcherController = FetcherController<Int, Int, Int>(
            scope = testScope,
            realFetcher = { key: Int ->
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
                    assertThat(fetcherController.fetcherSize()).isEqualTo(1)
                }.first()
        }

        val fetchers = (0 until fetcherCount).map {
            createFetcher()
        }
        fetchers.forEach {
            assertThat(it.await()).isEqualTo(
                Data(
                    value = 9,
                    origin = Fetcher
                )
            )
        }
        assertThat(fetcherController.fetcherSize()).isEqualTo(0)
        assertThat(createdCnt).isEqualTo(1)
    }
}
