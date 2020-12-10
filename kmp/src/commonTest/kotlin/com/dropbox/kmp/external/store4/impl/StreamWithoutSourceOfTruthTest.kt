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
package com.dropbox.kmp.external.store4.impl

import com.dropbox.kmp.external.store4.ResponseOrigin
import com.dropbox.kmp.external.store4.StoreBuilder
import com.dropbox.kmp.external.store4.StoreRequest
import com.dropbox.kmp.external.store4.StoreResponse
import com.dropbox.kmp.external.store4.testutil.FakeFetcher
import com.dropbox.kmp.external.store4.testutil.coroutines.TestCoroutineScope
import com.dropbox.kmp.external.store4.testutil.coroutines.runBlockingTest
import com.dropbox.kmp.external.store4.testutil.emitsExactly
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlin.jvm.JvmStatic
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@ExperimentalTime
@InternalCoroutinesApi
@FlowPreview
@ExperimentalCoroutinesApi
class StreamWithoutSourceOfTruthTest {
    private val testScope = TestCoroutineScope()

    // Todo - Implement multiplatform cache
//    @Test
//    fun streamWithoutPersisterEnabledCache() = testScope.runBlockingTest {
//        val fetcher = FakeFetcher(
//                3 to "three-1",
//                3 to "three-2"
//        )
//        val pipeline = StoreBuilder.from(fetcher)
//                .scope(testScope)
//                .build()
//        val twoItemsNoRefresh = async {
//            pipeline.stream(
//                    StoreRequest.cached(3, refresh = false)
//            ).take(3).toList()
//        }
//        delay(1_000) // make sure the async block starts first
//        pipeline.stream(StoreRequest.fresh(3)).emitsExactly(
//                StoreResponse.Loading(
//                        origin = ResponseOrigin.Fetcher
//                ),
//                StoreResponse.Data(
//                        value = "three-2",
//                        origin = ResponseOrigin.Fetcher
//                )
//        )
//        println("!")
//        val twoItemsNoRefreshResult = twoItemsNoRefresh.await()
//        assertTrue {
//            twoItemsNoRefreshResult.containsAll(
//                    listOf(StoreResponse.Loading(
//                            origin = ResponseOrigin.Fetcher
//                    ),
//                            StoreResponse.Data(
//                                    value = "three-1",
//                                    origin = ResponseOrigin.Fetcher
//                            ),
//                            StoreResponse.Data(
//                                    value = "three-2",
//                                    origin = ResponseOrigin.Fetcher
//                            ))
//            )
//        }
//    }
//
//    @Test
//    fun streamWithoutPersisterDisabledCache() = testScope.runBlockingTest {
//        val fetcher = FakeFetcher(
//                3 to "three-1",
//                3 to "three-2"
//        )
//        val pipeline = StoreBuilder.from(fetcher)
//                .scope(testScope).disableCache().build()
//        val twoItemsNoRefresh = async {
//            pipeline.stream(
//                    StoreRequest.cached(3, refresh = false)
//            ).take(3).toList()
//        }
//        delay(1_000) // make sure the async block starts first
//        pipeline.stream(StoreRequest.fresh(3)).emitsExactly(
//                StoreResponse.Loading(
//                        origin = ResponseOrigin.Fetcher
//                ),
//                StoreResponse.Data(
//                        value = "three-2",
//                        origin = ResponseOrigin.Fetcher
//                )
//        )
//        println("!")
//        val twoItemsNoRefreshResult = twoItemsNoRefresh.await()
//        assertTrue {
//            twoItemsNoRefreshResult.containsAll(
//                    listOf(StoreResponse.Loading(
//                            origin = ResponseOrigin.Fetcher
//                    ),
//                            StoreResponse.Data(
//                                    value = "three-1",
//                                    origin = ResponseOrigin.Fetcher
//                            ),
//                            StoreResponse.Data(
//                                    value = "three-2",
//                                    origin = ResponseOrigin.Fetcher
//                            ))
//            )
//        }
//    }

    companion object {
        @JvmStatic
        fun params() = arrayOf(true, false)
    }
}
