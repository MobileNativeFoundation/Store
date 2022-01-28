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
package com.dropbox.flow.multicast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class MulticastTest {
    private val testScope = TestCoroutineScope()

    private fun <T> createMulticaster(
        flow: Flow<T>,
        bufferSize: Int = 0,
        piggybackDownstream: Boolean = false
    ): Multicaster<T> {
        return Multicaster(
            scope = testScope,
            bufferSize = bufferSize,
            source = flow,
            onEach = {},
            piggybackingDownstream = piggybackDownstream
        )
    }

    @Test
    fun serialial_notShared() = testScope.runBlockingTest {
        var collectedCount = 0
        val activeFlow = createMulticaster(
            flow<String> {
                collectedCount++
                when (collectedCount) {
                    1 -> emitAll(flowOf("a", "b", "c"))
                    2 -> emitAll(flowOf("d", "e", "f"))
                    else -> throw AssertionError("should not collected more")
                }
            }
        )
        assertEquals(listOf("a", "b", "c"), activeFlow.newDownstream().toList())
        assertEquals(listOf("d", "e", "f"), activeFlow.newDownstream().toList())
    }

    @Test
    fun slowFastCollector() = testScope.runBlockingTest {
        val activeFlow = createMulticaster(
            flowOf("a", "b", "c").onStart {
                // make sure both registers on time so that no one drops a value
                delay(100)
            }
        )
        val c1 = async {
            activeFlow.newDownstream().onEach {
                delay(100)
            }.toList()
        }
        val c2 = async {
            activeFlow.newDownstream().onEach {
                delay(200)
            }.toList()
        }
        assertEquals(listOf("a", "b", "c"), c1.await())
        assertEquals(listOf("a", "b", "c"), c2.await())
    }

    @Test
    fun slowDispatcher() = testScope.runBlockingTest {
        val activeFlow = createMulticaster(
            flowOf("a", "b", "c").onEach {
                delay(100)
            }
        )
        val c1 = async {
            activeFlow.newDownstream().toList()
        }
        val c2 = async {
            activeFlow.newDownstream().toList()
        }
        assertEquals(listOf("a", "b", "c"), c1.await())
        assertEquals(listOf("a", "b", "c"), c2.await())
    }

    @Test
    fun lateToTheParty_arrivesAfterUpstreamClosed() = testScope.runBlockingTest {
        val activeFlow = createMulticaster(
            flowOf("a", "b", "c").onStart {
                delay(100)
            }
        )
        val c1 = async {
            activeFlow.newDownstream().toList()
        }
        val c2 = async {
            activeFlow.newDownstream().also {
                delay(110)
            }.toList()
        }
        assertEquals(listOf("a", "b", "c"), c1.await())
        assertEquals(listOf("a", "b", "c"), c2.await())
    }

    @Test
    fun lateToTheParty_arrivesBeforeUpstreamClosed() = testScope.runBlockingTest {
        var generationCounter = 0
        val activeFlow = createMulticaster(
            flow {
                val gen = generationCounter++
                check(gen < 2) {
                    "created one too many"
                }
                emit("a_$gen")
                delay(5)
                emit("b_$gen")
                delay(100)
            }
        )
        val c1 = async {
            activeFlow.newDownstream().onEach {
            }.toList()
        }
        val c2 = async {
            activeFlow.newDownstream().also {
                delay(3)
            }.toList()
        }
        val c3 = async {
            activeFlow.newDownstream().also {
                delay(20)
            }.toList()
        }
        val lists = listOf(c1, c2, c3).map {
            it.await()
        }
        assertEquals(listOf("a_0", "b_0"), lists[0])
        assertEquals(listOf("b_0"), lists[1])
        assertEquals(listOf("a_1", "b_1"), lists[2])
    }

    @Test
    fun upstreamError() = testScope.runBlockingTest {
        val exception =
            MyCustomException("hey")
        val activeFlow = createMulticaster(
            flow {
                emit("a")
                throw exception
            }
        )
        val receivedValue = CompletableDeferred<String>()
        val receivedError = CompletableDeferred<Throwable>()
        activeFlow.newDownstream()
            .onEach {
                check(receivedValue.isActive) {
                    "already received value"
                }
                receivedValue.complete(it)
            }.catch {
                check(receivedError.isActive) {
                    "already received error"
                }
                receivedError.complete(it)
            }.toList()
        assertEquals("a", receivedValue.await())
        val error = receivedError.await()
        assertEquals(exception, error)
    }

    @Test
    fun upstreamError_secondJustGetsError() = testScope.runBlockingTest {
        val exception =
            MyCustomException("hey")
        val dispatchedFirstValue = CompletableDeferred<Unit>()
        val registeredSecondCollector = CompletableDeferred<Unit>()
        val activeFlow = createMulticaster(
            flow {
                emit("a")
                dispatchedFirstValue.complete(Unit)
                registeredSecondCollector.await()
                yield() // yield to allow second collector to register
                throw exception
            }
        )
        launch {
            activeFlow.newDownstream().catch {}.toList()
        }
        // wait until the above collector registers and receives first value
        dispatchedFirstValue.await()
        val receivedValue = CompletableDeferred<String>()
        val receivedError = CompletableDeferred<Throwable>()
        activeFlow.newDownstream()
            .onStart {
                registeredSecondCollector.complete(Unit)
            }
            .onEach {
                receivedValue.complete(it)
            }.catch {
                check(receivedError.isActive) {
                    "already received error"
                }
                receivedError.complete(it)
            }.toList()
        val error = receivedError.await()
        assertEquals(exception, error)
        // test sanity, second collector never receives a value
        assertTrue(receivedValue.isActive)
    }

    @Test
    fun lateArrival_unregistersFromTheCorrectManager() = testScope.runBlockingTest {
        var collectedCount = 0
        var didntFinish = false
        val activeFlow = createMulticaster(
            flow {
                check(collectedCount < 2) {
                    "created 1 too many"
                }
                val index = ++collectedCount
                emit("a_$index")
                emit("b_$index")
                delay(100)
                if (index == 2) {
                    didntFinish = true
                }
            }
        )
        val firstCollector = async {
            activeFlow.newDownstream().onEach { delay(5) }.take(2).toList()
        }
        delay(11) // miss first two values
        val secondCollector = async {
            // this will come in a new channel
            activeFlow.newDownstream().take(2).toList()
        }
        assertEquals(listOf("a_1", "b_1"), firstCollector.await())
        assertEquals(listOf("a_2", "b_2"), secondCollector.await())
        assertEquals(2, collectedCount)
        delay(200)
        assertFalse(didntFinish)
    }

    @Test
    fun lateArrival_buffered() = testScope.runBlockingTest {
        var collectedCount = 0
        val activeFlow = Multicaster(
            scope = testScope,
            bufferSize = 2,
            source = flow {
                collectedCount++
                emit("a")
                delay(5)
                emit("b")
                emit("c")
                emit("d")
                delay(100)
                emit("e")
                // dont finish to see the buffer behavior
                delay(2000)
            },
            onEach = {}
        )
        val c1 = async {
            activeFlow.newDownstream().toList()
        }
        delay(4) // c2 misses first value
        val c2 = async {
            activeFlow.newDownstream().toList()
        }
        delay(50) // c3 misses first 4 values
        val c3 = async {
            activeFlow.newDownstream().toList()
        }
        delay(100) // c4 misses all values
        val c4 = async {
            activeFlow.newDownstream().toList()
        }
        assertEquals(listOf("a", "b", "c", "d", "e"), c1.await())
        assertEquals(listOf("a", "b", "c", "d", "e"), c2.await())
        assertEquals(listOf("c", "d", "e"), c3.await())
        assertEquals(listOf("d", "e"), c4.await())
        assertEquals(1, collectedCount)
    }

    @Test
    fun multipleCollections() = testScope.runBlockingTest {
        val activeFlow = Multicaster(
            scope = testScope,
            bufferSize = 0,
            source = flowOf(1, 2, 3),
            onEach = {}
        )
        assertEquals(listOf(1, 2, 3), activeFlow.newDownstream().toList())
        assertEquals(listOf(1, 2, 3), activeFlow.newDownstream().toList())
    }

    @Test
    fun lateArrival_arrivesWhenSuspended() = testScope.runBlockingTest {
        val activeFlow = versionedMulticaster(
            bufferSize = 0,
            collectionLimit = 1,
            values = listOf("a", "b", "c")
        )
        val unlockC1 = CompletableDeferred<Unit>()
        val c1 = async {
            activeFlow.newDownstream().collect {
                unlockC1.await()
                // never ack!
                throw RuntimeException("done 1")
            }
        }
        val c2 = async {
            activeFlow.newDownstream().toList()
        }
        testScope.runCurrent()
        assertFalse(c2.isActive)
        assertEquals(listOf("b_0", "c_0"), c2.await())
        unlockC1.complete(Unit)
    }

    @Test
    fun lateArrival_arrivesWhenSuspended_withBuffer() = testScope.runBlockingTest {
        val activeFlow = versionedMulticaster(
            bufferSize = 1,
            collectionLimit = 1,
            values = listOf("a", "b", "c")
        )
        val unlockC1 = CompletableDeferred<Unit>()
        val c1 = async {
            activeFlow.newDownstream().collect {
                unlockC1.await()
                // never ack!
                throw RuntimeException("done 1")
            }
        }
        val c2 = async {
            activeFlow.newDownstream().toList()
        }
        testScope.runCurrent()
        assertFalse(c2.isActive)
        assertEquals(listOf("a_0", "b_0", "c_0"), c2.await())
        unlockC1.complete(Unit)
    }

    @Test
    fun lateArrival_arrivesWhenSuspendedGetsNewStream() = testScope.runBlockingTest {
        val activeFlow = versionedMulticaster(
            bufferSize = 0,
            collectionLimit = 2,
            values = listOf("a")
        )
        val unlockC1 = CompletableDeferred<Unit>()
        val c1 = async {
            activeFlow.newDownstream().collect {
                unlockC1.await()
                throw RuntimeException("done 1")
            }
        }
        val c2 = async {
            activeFlow.newDownstream().toList()
        }
        testScope.runCurrent()
        assertFalse(c2.isActive)
        assertEquals(listOf("a_1"), c2.await())
        unlockC1.complete(Unit)
    }

    @Test
    fun closed_whileCollecting() = testScope.runBlockingTest {
        var collectionCount = 0
        val multicaster = createMulticaster(
            flow {
                collectionCount++
                emit(1)
                // suspend forever
                suspendCancellableCoroutine<Unit> {}
            }
        )
        val collection = async {
            multicaster.newDownstream().toList()
        }
        runCurrent()
        assertTrue(collection.isActive)
        multicaster.close()
        runCurrent()
        assertTrue(collection.isCompleted)
        assertEquals(listOf(1), collection.await())
    }

    @Test
    fun closed_subscriberAfterClose() = testScope.runBlockingTest {
        var collectionCount = 0
        val multicaster = createMulticaster(
            flow {
                collectionCount++
                emit(1)
                // suspend forever
                suspendCancellableCoroutine<Unit> {}
            }
        )
        multicaster.close()
        // now add a subscriber, should just close immediately
        runCurrent()
        val collection = async {
            multicaster.newDownstream().toList()
        }
        runCurrent()
        assertFalse(collection.isActive)
        assertTrue(collection.await().isEmpty())
    }

    @Test
    fun closed_additionalSubscriberAfterClose_withBuffer() = testScope.runBlockingTest {
        var collectionCount = 0
        val multicaster = createMulticaster(
            flow = flow {
                collectionCount++
                emit(1)
                // suspend forever
                suspendCancellableCoroutine<Unit> {}
            },
            bufferSize = 10
        )
        async {
            multicaster.newDownstream().toList()
        }
        runCurrent()
        multicaster.close()
        runCurrent()
        // now add a new subscriber, should just close immediately
        // note that even there is a buffer, closing multicast releases all resources so buffer
        // will be gone as well.
        val collection2 = async {
            multicaster.newDownstream().toList()
        }
        runCurrent()
        assertFalse(collection2.isActive)
        assertTrue(collection2.await().isEmpty())
    }

    @Test
    fun `GIVEN piggybackDownstream AND piggybackOnly downstream followed by regular downstream WHEN add piggback downstream AND add downstream THEN upstream does not start until 2nd downstream is added AND both get value`() =
        testScope.runBlockingTest {
            var createCount = 0
            val source = flow {
                createCount++
                emit("value")
            }
            val multicaster =
                createMulticaster(flow = source, piggybackDownstream = true)
            val piggybackDownstream = multicaster.newDownstream(piggybackOnly = true)
            val piggybackValue = testScope.async { piggybackDownstream.first() }
            testScope.advanceUntilIdle()
            assertEquals(0, createCount)
            assertFalse(piggybackValue.isCompleted)

            val downstream = multicaster.newDownstream(piggybackOnly = false)
            val value = testScope.async { downstream.first() }
            testScope.advanceUntilIdle()
            assertEquals(1, createCount)
            assertTrue(piggybackValue.isCompleted)
            assertEquals("value", piggybackValue.getCompleted())
            assertTrue(value.isCompleted)
            assertEquals("value", value.getCompleted())
        }

    @Test
    fun `GIVEN no piggybackDownstream WHEN adding a piggybackOnly downstream THEN throws IllegalStateException`() {
        assertFailsWith(IllegalStateException::class, "Must fail with IllegalStateException") {
            testScope.runBlockingTest {
                val multicaster = createMulticaster(flowOf("a"), piggybackDownstream = false)
                multicaster.newDownstream(piggybackOnly = true)
            }
        }
    }

    private fun versionedMulticaster(
        bufferSize: Int = 0,
        collectionLimit: Int,
        values: List<String>
    ): Multicaster<String> {
        var counter = 0
        return Multicaster(
            scope = testScope,
            bufferSize = bufferSize,
            source = flow<String> {
                val id = counter++
                assertTrue(counter <= collectionLimit)
                emitAll(
                    values.asFlow().map {
                        "${it}_$id"
                    }
                )
            },
            onEach = {}
        )
    }

    class MyCustomException(val x: String) : RuntimeException("hello") {
        override fun toString() = "custom$x"
    }
}
