package com.dropbox.android.external.store4.impl.multicast

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Multicaster tests where downstream is not closed even when upstream is closed.
 * It basically waits until there is another reason to enable upstream and will receive those
 * values as well.
 */
@FlowPreview
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class InfiniteMulticastTest {
    private val testScope = TestCoroutineScope()
    private val dispatchLog = mutableListOf<String>()

    private fun <T> createMulticaster(f: () -> Flow<T>): Multicaster<T> {
        return Multicaster(
            scope = testScope,
            bufferSize = 0,
            source = f,
            piggybackingDownstream = true,
            onEach = {
                dispatchLog.add(it.toString())
            })
    }

    @Test
    fun piggyback() = testScope.runBlockingTest {
        var createdCount = 0
        val activeFlow = createMulticaster {
            val id = createdCount++
            flowOf("a$id", "b$id", "c$id").onStart {
                // make sure both registers on time so that no one drops a value
                delay(100)
            }
        }
        val c1 = async {
            activeFlow.create().onEach {
                delay(100)
            }.take(6).toList()
        }
        val c2 = async {
            activeFlow.create().onEach {
                delay(200)
            }.take(6).toList()
        }
        // ensure first flow finishes
        delay(10_000)
        // add another
        val c3 = activeFlow.create().take(3).toList()
        assertThat(c1.await())
            .isEqualTo(listOf("a0", "b0", "c0", "a1", "b1", "c1"))
        assertThat(c2.await())
            .isEqualTo(listOf("a0", "b0", "c0", "a1", "b1", "c1"))
        assertThat(c3)
            .isEqualTo(listOf("a1", "b1", "c1"))
        assertThat(createdCount).isEqualTo(2)
    }

    @Test
    fun piggyback_newStreamClosesEarly() = testScope.runBlockingTest {
        var createdCount = 0
        val activeFlow = createMulticaster {
            val id = createdCount++
            flowOf("a$id", "b$id", "c$id").onStart {
                // make sure both registers on time so that no one drops a value
                delay(100)
            }
        }
        val c1 = async {
            activeFlow.create().onEach {
                delay(100)
            }.take(6).toList()
        }
        val c2 = async {
            activeFlow.create().onEach {
                delay(200)
            }.take(6).toList()
        }
        // ensure first flow finishes
        delay(10_000)
        // add another
        val c3 = activeFlow.create().take(1).toList()
        assertThat(c1.await())
            .isEqualTo(listOf("a0", "b0", "c0", "a1", "b1", "c1"))
        assertThat(c2.await())
            .isEqualTo(listOf("a0", "b0", "c0", "a1", "b1", "c1"))
        assertThat(c3)
            .isEqualTo(listOf("a1"))
        assertThat(createdCount).isEqualTo(2)
    }

    @Test
    fun piggyback_oldStreamClosesEarly() = testScope.runBlockingTest {
        var createdCount = 0
        val activeFlow = createMulticaster {
            val id = createdCount++
            flowOf("a$id", "b$id", "c$id").onStart {
                // make sure both registers on time so that no one drops a value
                delay(100)
            }
        }
        val c1 = async {
            activeFlow.create().onEach {
                delay(100)
            }.take(4).toList()
        }
        val c2 = async {
            activeFlow.create().onEach {
                delay(200)
            }.take(5).toList()
        }
        // ensure first flow finishes
        delay(10_000)
        // add another
        val c3 = activeFlow.create().take(3).toList()
        assertThat(c1.await())
            .isEqualTo(listOf("a0", "b0", "c0", "a1"))
        assertThat(c2.await())
            .isEqualTo(listOf("a0", "b0", "c0", "a1", "b1"))
        assertThat(c3)
            .isEqualTo(listOf("a1", "b1", "c1"))
        assertThat(createdCount).isEqualTo(2)
    }

    @Test
    fun piggyback_allStreamsCloseSearch() = testScope.runBlockingTest {
        var createdCount = 0
        val activeFlow = createMulticaster {
            val id = createdCount++
            flowOf("a$id", "b$id", "c$id").transform {
                // really slow to test early termination
                delay(1_000)
                emit(it)
            }
        }
        val c1 = async {
            activeFlow.create().onEach {
                delay(100)
            }.take(4).toList()
        }
        val c2 = async {
            activeFlow.create().onEach {
                delay(200)
            }.take(5).toList()
        }
        // ensure first flow finishes
        delay(10_000)
        // add another
        val c3 = activeFlow.create().take(1).toList()
        assertThat(c1.await())
            .isEqualTo(listOf("a0", "b0", "c0", "a1"))
        assertThat(c2.await())
            .isEqualTo(listOf("a0", "b0", "c0", "a1", "b1"))
        assertThat(c3)
            .isEqualTo(listOf("a1"))
        assertThat(createdCount).isEqualTo(2)
        // make sure we didn't keep upsteam too long
        assertThat(dispatchLog).containsExactly(
            "a0", "b0", "c0", "a1", "b1"
        )
    }
}
