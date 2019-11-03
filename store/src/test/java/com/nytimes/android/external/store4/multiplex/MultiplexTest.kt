package com.nytimes.android.external.store4.multiplex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@FlowPreview
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class MultiplexTest {
    private val testScope = TestCoroutineScope()

    private fun <T> createMultiplexer(f: () -> Flow<T>): Multiplexer<T> {
        return Multiplexer(testScope, 0, f, {})
    }

    @Test
    fun serialial_notShared() = testScope.runBlockingTest {
        var createCnt = 0
        val activeFlow = createMultiplexer {
            createCnt++
            when (createCnt) {
                1 -> flowOf("a", "b", "c")
                2 -> flowOf("d", "e", "f")
                else -> throw AssertionError("should not create more")
            }
        }
        assertThat(activeFlow.create().toList())
            .isEqualTo(listOf("a", "b", "c"))
        assertThat(activeFlow.create().toList())
            .isEqualTo(listOf("d", "e", "f"))
    }

    @Test
    fun slowFastCollector() = testScope.runBlockingTest {
        val activeFlow = createMultiplexer {
            flowOf("a", "b", "c").onStart {
                // make sure both registers on time so that no one drops a value
                delay(100)
            }
        }
        val c1 = async {
            activeFlow.create().onEach {
                delay(100)
            }.toList()
        }
        val c2 = async {
            activeFlow.create().onEach {
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
        val activeFlow = createMultiplexer {
            flowOf("a", "b", "c").onEach {
                delay(100)
            }
        }
        val c1 = async {
            activeFlow.create().toList()
        }
        val c2 = async {
            activeFlow.create().toList()
        }
        assertThat(c1.await()).isEqualTo(listOf("a", "b", "c"))
        assertThat(c2.await()).isEqualTo(listOf("a", "b", "c"))
    }

    @Test
    fun lateToTheParty_arrivesAfterUpstreamClosed() = testScope.runBlockingTest {
        val activeFlow = createMultiplexer {
            flowOf("a", "b", "c").onStart {
                delay(100)
            }
        }
        val c1 = async {
            activeFlow.create().toList()
        }
        val c2 = async {
            activeFlow.create().also {
                delay(110)
            }.toList()
        }
        assertThat(c1.await()).isEqualTo(listOf("a", "b", "c"))
        assertThat(c2.await()).isEqualTo(listOf("a", "b", "c"))
    }

    @Test
    fun lateToTheParty_arrivesBeforeUpstreamClosed() = testScope.runBlockingTest {
        var generationCounter = 0
        val activeFlow = createMultiplexer {
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
        }
        val c1 = async {
            activeFlow.create().onEach {
            }.toList()
        }
        val c2 = async {
            activeFlow.create().also {
                delay(3)
            }.toList()
        }
        val c3 = async {
            activeFlow.create().also {
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
        val activeFlow = createMultiplexer {
            flow {
                emit("a")
                throw exception
            }
        }
        val receivedValue = CompletableDeferred<String>()
        val receivedError = CompletableDeferred<Throwable>()
        activeFlow.create()
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
        val activeFlow = createMultiplexer {
            flow {
                emit("a")
                dispatchedFirstValue.complete(Unit)
                registeredSecondCollector.await()
                yield() //yield to allow second collector to register
                throw exception
            }
        }
        launch {
            activeFlow.create().catch {

            }.toList()
        }
        // wait until the above collector registers and receives first value
        dispatchedFirstValue.await()
        val receivedValue = CompletableDeferred<String>()
        val receivedError = CompletableDeferred<Throwable>()
        activeFlow.create()
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
        var createdCount = 0
        var didntFinish = false
        val activeFlow = createMultiplexer {
            flow {
                check(createdCount < 2) {
                    "created 1 too many"
                }
                val index = ++createdCount
                emit("a_$index")
                emit("b_$index")
                delay(100)
                if (index == 2) {
                    didntFinish = true
                }
            }
        }
        val firstCollector = async {
            activeFlow.create().onEach { delay(5) }.take(2).toList()
        }
        delay(11) // miss first two values
        val secondCollector = async {
            // this will come in a new channel
            activeFlow.create().take(2).toList()
        }
        assertThat(firstCollector.await()).isEqualTo(listOf("a_1", "b_1"))
        assertThat(secondCollector.await()).isEqualTo(listOf("a_2", "b_2"))
        assertThat(createdCount).isEqualTo(2)
        delay(200)
        assertThat(didntFinish).isEqualTo(false)
    }

    @Test
    fun lateArrival_buffered() = testScope.runBlockingTest {
        var createdCount = 0
        val activeFlow = Multiplexer(
            scope = testScope,
            bufferSize = 2,
            source = {
                createdCount++
                flow {
                    emit("a")
                    delay(5)
                    emit("b")
                    emit("c")
                    emit("d")
                    delay(100)
                    emit("e")
                    // dont finish to see the buffer behavior
                    delay(2000)
                }
            },
            onEach = {}
        )
        val c1 = async {
            activeFlow.create().toList()
        }
        delay(4)// c2 misses first value
        val c2 = async {
            activeFlow.create().toList()
        }
        delay(50) // c3 misses first 4 values
        val c3 = async {
            activeFlow.create().toList()
        }
        delay(100) // c4 misses all values
        val c4 = async {
            activeFlow.create().toList()
        }
        assertThat(c1.await()).isEqualTo(listOf("a", "b", "c", "d", "e"))
        assertThat(c2.await()).isEqualTo(listOf("a", "b", "c", "d", "e"))
        assertThat(c3.await()).isEqualTo(listOf("c", "d", "e"))
        assertThat(c4.await()).isEqualTo(listOf("d", "e"))
        assertThat(createdCount).isEqualTo(1)
    }

    class MyCustomException(val x: String) : RuntimeException("hello") {
        override fun toString() = "custom$x"
    }
}
