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
package com.dropbox.android.external.store4.testutil

import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.FetcherResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

class FakeFetcher<Key : Any, Output : Any>(
    private vararg val responses: Pair<Key, Output>
) : Fetcher<Key, Output> {
    private var index = 0

    override fun fetch(key: Key): Flow<FetcherResult<Output>> {
        if (index >= responses.size) {
            throw AssertionError("unexpected fetch request")
        }
        val pair = responses[index++]
        assertThat(pair.first).isEqualTo(key)
        return flowOf(FetcherResult.Data(pair.second))
    }
}

class FakeFlowingFetcher<Key : Any, Output : Any>(
    private vararg val responses: Pair<Key, Output>
) : Fetcher<Key, Output> {
    override fun fetch(key: Key) = flow {
        responses.filter {
            it.first == key
        }.forEach {
            // we delay here to avoid collapsing fetcher values, otherwise, there is a
            // possibility that consumer won't be fast enough to get both values before new
            // value overrides the previous one.
            delay(1)
            emit(FetcherResult.Data(it.second))
        }
    }
}
