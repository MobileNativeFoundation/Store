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
package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@FlowPreview
@ExperimentalCoroutinesApi
@RunWith(Parameterized::class)
class StreamWithoutSourceOfTruthTest(
    private val enableCache: Boolean
) {
    private val testScope = TestCoroutineScope()

    @Test
    fun streamWithoutPersister() = testScope.runBlockingTest {
        val fetcher = FakeFetcher(
                3 to "three-1",
                3 to "three-2"
        )
        val pipeline = StoreBuilder.fromNonFlow(fetcher::fetch)
                .scope(testScope)
                .let {
                    if (enableCache) {
                        it
                    } else {
                        it.disableCache()
                    }
                }.build()
        val twoItemsNoRefresh = async {
            pipeline.stream(
                    StoreRequest.cached(3, refresh = false)
            ).take(3).toList()
        }
        delay(1_000) // make sure the async block starts first
        pipeline.stream(StoreRequest.fresh(3)).assertItems(
                StoreResponse.Loading(
                        origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                        value = "three-2",
                        origin = ResponseOrigin.Fetcher
                )
        )
        println("!")
        assertThat(twoItemsNoRefresh.await()).containsExactly(
                StoreResponse.Loading(
                        origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                        value = "three-1",
                        origin = ResponseOrigin.Fetcher
                ),
                StoreResponse.Data(
                        value = "three-2",
                        origin = ResponseOrigin.Fetcher
                )
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "enableCache={0}")
        fun params() = arrayOf(true, false)
    }
}
