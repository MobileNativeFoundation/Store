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

package com.dropbox.kmp.external.store4.testutil

import com.dropbox.kmp.external.store4.testutil.coroutines.TestCoroutineScope
import com.dropbox.kmp.external.store4.testutil.coroutines.runBlockingTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@InternalCoroutinesApi
@OptIn(ExperimentalCoroutinesApi::class)
@ExperimentalTime
class TurbineTest {

    private val testScope = TestCoroutineScope()

    @Test
    fun exactFlow() = testScope.runBlockingTest {
        val src = flowOf(1, 2, 3)
        src.emitsExactlyAndCompletes(1, 2, 3)
    }

    @Test
    fun exactFlow_neverEnding() = testScope.runBlockingTest {
        val src = flow {
            emitAll(flowOf(1, 2, 3))
            // suspend forever
            suspendCancellableCoroutine<Unit> { }
        }
        src.emitsExactly(1, 2, 3)
    }

    @Test
    fun lessThanExpectedItems() = testScope.runBlockingTest {
        val src = flowOf(1, 2, 3)
        val exception = try {
            src.emitsExactly(1, 2, 3, 4)
            null
        } catch (error: Throwable) {
            error
        }
        assertTrue {
            exception?.message?.contains("""Expected item but found Complete""".trimIndent()) ?: false
        }
    }

    // Todo - reimplement assertion error message checking?
    @Test
    fun outOfOrder() = testScope.runBlockingTest {
        val src = flowOf(1, 3, 2)

        assertFails {
            src.emitsExactlyAndCompletes(1, 2, 3)
        }
    }

    @Test
    fun moreThanExpectedItems() = testScope.runBlockingTest {
        val src = flowOf(1, 2, 3, 4)

        assertFails {
            src.emitsExactlyAndCompletes(1, 2, 3)
        }
    }
}
