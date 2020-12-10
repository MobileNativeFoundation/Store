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
package com.dropbox.kmp.external.store4.testutil

import com.dropbox.kmp.external.store4.testutil.coroutines.TestCoroutineScope
import com.dropbox.kmp.external.store4.testutil.coroutines.runBlockingTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
@InternalCoroutinesApi
class SimplePersisterAsFlowableTest {
    private val barcode = "a" to "b"
    private val testScope = TestCoroutineScope()
    private val otherScope = TestCoroutineScope()

    @Test
    fun testSimple() = testScope.runBlockingTest {
        val (flowable, written) = create("a", "b")
        val read = flowable.flowReader(barcode).take(1).toCollection(mutableListOf())
        assertTrue(read.contains("a"))
    }

    @Test
    fun writeInvalidation() = testScope.runBlockingTest {
        val (flowable, written) = create("a", "b")
        flowable.flowWriter("another" to "value", "dsa")
        val collectedFirst = CompletableDeferred<Unit>()
        val collectedValues = CompletableDeferred<List<String?>>()
        otherScope.launch {
            collectedValues.complete(
                flowable
                    .flowReader(barcode)
                    .onEach {
                        if (collectedFirst.isActive) {
                            collectedFirst.complete(Unit)
                        }
                    }
                    .take(2)
                    .toList()
            )
        }
        collectedFirst.await()
        flowable.flowWriter(barcode, "x")
        otherScope.advanceUntilIdle()
        assertEquals(listOf("a", "b"), collectedValues.await())
        assertEquals(listOf("dsa", "x"), written)
    }

    /**
     * invalidating inside the read should call itself again. This test fails if it cannot take 2
     * (in other words, writing inside the read failed to notify)
     */
    @Test
    fun nestedInvalidation() = testScope.runBlockingTest {
        val (flowable, written) = create("a", "b")
        flowable.flowReader(barcode).take(2).collect {
            flowable.flowWriter(barcode, "x")
        }
    }

    private fun create(
        vararg values: String
    ): Pair<SimplePersisterAsFlowable<Pair<String, String>, String, String>, List<String>> {
        var readIndex = 0
        val written = mutableListOf<String>()
        return SimplePersisterAsFlowable<Pair<String, String>, String, String>(
            reader = {
                if (readIndex >= values.size) {
                    throw AssertionError("should not've read this many")
                }
                values[readIndex++]
            },
            writer = { _: Pair<String, String>, value: String ->
                written.add(value)
            },
            delete = {
                TODO("not implemented")
            }
        ) to written
    }
}
