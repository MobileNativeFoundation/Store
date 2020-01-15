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

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScope

/**
 * Takes all items in the flow that are available by collecting on it as long as there are active
 * jobs in the given [TestCoroutineScope].
 *
 * It ensures all expected items are dispatched as well as no additional unexpected items are
 * dispatched.
 */
@ExperimentalCoroutinesApi
suspend fun <T> Flow<T>.assertItems(scope: TestCoroutineScope, vararg expected: T) {
    val collectedSoFar = mutableListOf<T>()
    val collectJob = scope.launch {
        this@assertItems.collect {
            collectedSoFar.add(it)
        }
    }
    scope.advanceUntilIdle()
    collectJob.cancel()
    assertThat(collectedSoFar).isEqualTo(expected.toList())
}
