package org.mobilenativefoundation.store.rx2.test

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



import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle

@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> TestCoroutineScope.assertThat(flow: Flow<T>): FlowSubject<T> {
    return Truth.assertAbout(FlowSubject.Factory<T>(this)).that(flow)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class FlowSubject<T> constructor(
        failureMetadata: FailureMetadata,
        private val testCoroutineScope: TestCoroutineScope,
        private val actual: Flow<T>
) : Subject(failureMetadata, actual) {
    /**
     * Takes all items in the flow that are available by collecting on it as long as there are
     * active jobs in the given [TestCoroutineScope].
     *
     * It ensures all expected items are dispatched as well as no additional unexpected items are
     * dispatched.
     */
    suspend fun emitsExactly(vararg expected: T) {
        val collectedSoFar = mutableListOf<T>()
        val collectionCoroutine = testCoroutineScope.async {
            actual.collect {
                collectedSoFar.add(it)
                if (collectedSoFar.size > expected.size) {
                    assertWithMessage("Too many emissions in the flow (only first additional item is shown)")
                            .that(collectedSoFar)
                            .isEqualTo(expected)
                }
            }
        }
        testCoroutineScope.advanceUntilIdle()
        if (!collectionCoroutine.isActive) {
            collectionCoroutine.getCompletionExceptionOrNull()?.let {
                throw it
            }
        }
        collectionCoroutine.cancelAndJoin()
        assertWithMessage("Flow didn't exactly emit expected items")
                .that(collectedSoFar)
                .isEqualTo(expected.toList())
    }

    class Factory<T>(
            private val testCoroutineScope: TestCoroutineScope
    ) : Subject.Factory<FlowSubject<T>, Flow<T>> {
        override fun createSubject(metadata: FailureMetadata, actual: Flow<T>): FlowSubject<T> {
            return FlowSubject(
                    failureMetadata = metadata,
                    actual = actual,
                    testCoroutineScope = testCoroutineScope
            )
        }
    }
}