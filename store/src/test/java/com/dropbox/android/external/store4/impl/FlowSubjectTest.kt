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

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
@UseExperimental(ExperimentalCoroutinesApi::class)
class FlowSubjectTest {
    // Can't use ExpectFailure in these tests because it is not a suspend function.
    private val testScope = TestCoroutineScope()

    @Test
    fun exactFlow() = testScope.runBlockingTest {
        val src = flowOf(1, 2, 3)
        assertThat(src).emitsExactly(1, 2, 3)
    }

    @Test
    fun lessThanExpectedItems() = testScope.runBlockingTest {
        val src = flowOf(1, 2, 3)
        val exception = try {
            assertThat(src).emitsExactly(1, 2, 3, 4)
            null
        } catch (error: Throwable) {
            error
        }
        assertThat(exception).hasMessageThat().contains(
            """
                    Flow didn't exactly emit expected items
                    missing (1): 4
                    ---
                    expected   : [1, 2, 3, 4]
                    but was    : [1, 2, 3]
            """.trimIndent()
        )
    }

    @Test
    fun outOfOrder() = testScope.runBlockingTest {
        val src = flowOf(1, 3, 2)
        val exception = try {
            assertThat(src).emitsExactly(1, 2, 3)
            null
        } catch (error: Throwable) {
            error
        }
        assertThat(exception).hasMessageThat().contains(
            """
            Flow didn't exactly emit expected items
            contents match, but order was wrong
            expected: [1, 2, 3]
            but was : [1, 3, 2]
            """.trimIndent()
        )

    }

    @Test
    fun moreThanExpectedItems() = testScope.runBlockingTest {
        val src = flowOf(1, 2, 3, 4)
        val exception = try {
            assertThat(src).emitsExactly(1, 2, 3)
            null
        } catch (error: Throwable) {
            error
        }
        assertThat(exception).hasMessageThat().contains(
            """
            Too many emissions in the flow
            expected: [1, 2, 3]
            but was : [1, 2, 3, 4]
        """.trimIndent()
        )
    }
}
