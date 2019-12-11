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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.assertj.core.api.Assertions

/**
 * Asserts only the [expected] items by just taking that many from the stream
 *
 * Use this when Pipeline has an infinite part (e.g. Persister or a never ending fetcher)
 */
@ExperimentalCoroutinesApi
suspend fun <T> Flow<T>.assertItems(vararg expected: T) {
    Assertions.assertThat(this.take(expected.size).toList())
            .isEqualTo(expected.toList())
}
