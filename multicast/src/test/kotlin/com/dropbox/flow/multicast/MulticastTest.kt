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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.processNextEventInCurrentThread
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.yield
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@FlowPreview
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class MulticastTest {
    private val testScope = TestCoroutineScope()

    private fun <T> createMulticaster(f: Flow<T>): Multicaster<T> {
        return Multicaster(
            scope = testScope,
            bufferSize = 0,
            source = f,
            onEach = {})
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
        assertThat(activeFlow.flow.toList())
            .isEqualTo(listOf("a", "b", "c"))
        assertThat(activeFlow.flow.toList())
            .isEqualTo(listOf("d", "e", "f"))
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
            activeFlow.flow.onEach {
                delay(100)
            }.toList()
        }
        val c2 = async {
            activeFlow.flow.onEach {
                delay(200)
            }.toList()
        }
        assertThat(c1.await())
            .isEqualTo(listOf("a", "b", "c"))
        assertThat(c2.await())
            .isEqualTo(listOf("a", "b", "c"))
    }

    @Test
    fun slowDispatcher() = testScope.runBlockingTest {
        val activeFlow = createMulticaster(
            flowOf("a", "b", "c").onEach {
                delay(100)
            }
        )
        val c1 = async {
            activeFlow.flow.toList()
        }
        val c2 = async {
            activeFlow.flow.toList()
        }
        assertThat(c1.await()).isEqualTo(listOf("a", "b", "c"))
        assertThat(c2.await()).isEqualTo(listOf("a", "b", "c"))
    }

    @Test
    fun lateToTheParty_arrivesAfterUpstreamClosed() = testScope.runBlockingTest {
        val activeFlow = createMulticaster(
            flowOf("a", "b", "c").onStart {
                delay(100)
            }
        )
        val c1 = async {
            activeFlow.flow.toList()
        }
        val c2 = async {
            activeFlow.flow.also {
                delay(110)
            }.toList()
        }
        assertThat(c1.await()).isEqualTo(listOf("a", "b", "c"))
        assertThat(c2.await()).isEqualTo(listOf("a", "b", "c"))
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
            activeFlow.flow.onEach {
            }.toList()
        }
        val c2 = async {
            activeFlow.flow.also {
                delay(3)
            }.toList()
        }
        val c3 = async {
            activeFlow.flow.also {
                delay(20)
            }.toList()
        }
        val lists = listOf(c1, c2, c3).map {
            it.await()
        }
        assertThat(lists[0]).isEqualTo(listOf("a_0", "b_0"))
        assertThat(lists[1]).isEqualTo(listOf("b_0"))
        assertThat(lists[2]).isEqualTo(listOf("a_1", "b_1"))
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
        activeFlow.flow
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
        assertThat(receivedValue.await()).isEqualTo("a")
        val error = receivedError.await()
        assertThat(error).isEqualTo(exception)
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
            activeFlow.flow.catch {}.toList()
        }
        // wait until the above collector registers and receives first value
        dispatchedFirstValue.await()
        val receivedValue = CompletableDeferred<String>()
        val receivedError = CompletableDeferred<Throwable>()
        activeFlow.flow
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
        assertThat(error).isEqualTo(exception)
        // test sanity, second collector never receives a value
        assertThat(receivedValue.isActive).isTrue()
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
            activeFlow.flow.onEach { delay(5) }.take(2).toList()
        }
        delay(11) // miss first two values
        val secondCollector = async {
            // this will come in a new channel
            activeFlow.flow.take(2).toList()
        }
        assertThat(firstCollector.await()).isEqualTo(listOf("a_1", "b_1"))
        assertThat(secondCollector.await()).isEqualTo(listOf("a_2", "b_2"))
        assertThat(collectedCount).isEqualTo(2)
        delay(200)
        assertThat(didntFinish).isEqualTo(false)
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
            activeFlow.flow.toList()
        }
        delay(4) // c2 misses first value
        val c2 = async {
            activeFlow.flow.toList()
        }
        delay(50) // c3 misses first 4 values
        val c3 = async {
            activeFlow.flow.toList()
        }
        delay(100) // c4 misses all values
        val c4 = async {
            activeFlow.flow.toList()
        }
        assertThat(c1.await()).isEqualTo(listOf("a", "b", "c", "d", "e"))
        assertThat(c2.await()).isEqualTo(listOf("a", "b", "c", "d", "e"))
        assertThat(c3.await()).isEqualTo(listOf("c", "d", "e"))
        assertThat(c4.await()).isEqualTo(listOf("d", "e"))
        assertThat(collectedCount).isEqualTo(1)
    }

    @Test
    fun multipleCollections() = testScope.runBlockingTest {
        val activeFlow = Multicaster(
                scope = testScope,
                bufferSize = 0,
                source = flowOf(1, 2, 3),
                onEach = {}
        )
        assertThat(activeFlow.flow.toList()).isEqualTo(listOf(1, 2, 3))
        assertThat(activeFlow.flow.toList()).isEqualTo(listOf(1, 2, 3))
    }

    @Test
    fun lateArrival_arrivesWhenSuspended() = testScope.runBlockingTest {
        val activeFlow = Multicaster(
                scope = testScope,
                bufferSize = 0,
                source = flowOf(1, 2, 3),
                onEach = {}
        )
        val unlockC1 = CompletableDeferred<Unit>()
        val c1 = async {
            activeFlow.flow.collect {
                unlockC1.await()
                throw RuntimeException("done 1")
            }
        }
        val c2 = async {
            activeFlow.flow.toList()
        }
        testScope.runCurrent()
        assertThat(c2.isActive).isTrue()
        unlockC1.complete(Unit)
        testScope.runCurrent()
        assertThat(c2.isActive).isFalse()
        assertThat(c2.await()).isEqualTo(listOf(2,3))
    }

    @Test
    fun lateArrival_arrivesWhenSuspendedGetsNewStream() = testScope.runBlockingTest {
        var counter = 0
        val activeFlow = Multicaster(
                scope = testScope,
                bufferSize = 0,
                source = flow<Int>{
                    val id = counter ++
                    emitAll(flowOf(id))
                },
                onEach = {}
        )
        val unlockC1 = CompletableDeferred<Unit>()
        val c1 = async {
            activeFlow.flow.collect {
                unlockC1.await()
                throw RuntimeException("done 1")
            }
        }
        val c2 = async {
            activeFlow.flow.toList()
        }
        testScope.runCurrent()
        assertThat(c2.isActive).isTrue()
        unlockC1.complete(Unit)
        testScope.runCurrent()
        assertThat(c2.isActive).isFalse()
        assertThat(c2.await()).isEqualTo(listOf(1))
    }

    class MyCustomException(val x: String) : RuntimeException("hello") {
        override fun toString() = "custom$x"
    }
}
