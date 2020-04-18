/*
 * Copyright 2020 Google LLC
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
package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.FetcherResponse
import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.android.external.store4.testutil.assertThat
import com.google.common.truth.Truth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
@OptIn(ExperimentalStdlibApi::class)
class FetcherResponseTest {
    private val testScope = TestCoroutineScope()
    @Test
    fun `given a Fetcher that returns FetcherResponse, its exceptions should not be caught`() {
        val store = StoreBuilder.from<Int, Int>(
            Fetcher.fromNonFlowingFetcher {
                throw RuntimeException("don't catch me")
            }
        ).scope(testScope).build()

        val result = kotlin.runCatching {
            testScope.runBlockingTest {

                val result = store.stream(StoreRequest.fresh(1)).toList()
                Truth.assertThat(result).isEmpty()
            }
        }
        Truth.assertThat(result.isFailure).isTrue()
        Truth.assertThat(result.exceptionOrNull()).hasMessageThat().contains(
            "don't catch me"
        )
    }

    @Test
    fun `given a Fetcher that emits FetcherResponse, it can emit value after an error`() {
        val exception = RuntimeException("first error")
        testScope.runBlockingTest {
            val store = StoreBuilder.from(
                object : Fetcher<Int, String> {
                    override suspend fun invoke(key: Int): Flow<FetcherResponse<String>> {
                        return flowOf(
                            FetcherResponse.Error(exception),
                            FetcherResponse.Value("$key")
                        )
                    }
                }
            ).scope(testScope).build()
            assertThat(store.stream(StoreRequest.fresh(1)))
                .emitsExactly(
                    StoreResponse.Loading(ResponseOrigin.Fetcher),
                    StoreResponse.Error(exception, ResponseOrigin.Fetcher),
                    StoreResponse.Data("1", ResponseOrigin.Fetcher)
                )
        }
    }
}