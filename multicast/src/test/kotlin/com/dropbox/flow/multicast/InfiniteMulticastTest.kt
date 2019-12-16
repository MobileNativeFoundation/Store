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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    private fun <T> createMulticaster(f: Flow<T>): Multicaster<T> {
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
        val activeFlow = createMulticaster(flow {
            val id = createdCount++
            listOf("a", "b", "c").forEach {
                emit("$it$id")
            }
        }.onStart {
            // make sure both registers on time so that no one drops a value
            delay(100)
        })
        val c1 = async {
            activeFlow.flow.onEach {
                delay(100)
            }.take(6).toList()
        }
        val c2 = async {
            activeFlow.flow.onEach {
                delay(200)
            }.take(6).toList()
        }
        // ensure first flow finishes
        delay(10_000)
        // add another
        val c3 = activeFlow.flow.take(3).toList()
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
        val activeFlow = createMulticaster(flow {
            val id = createdCount++
            listOf("a", "b", "c").forEach {
                emit("$it$id")
            }
        }.onStart {
            // make sure both registers on time so that no one drops a value
            delay(100)
        })
        val c1 = async {
            activeFlow.flow.onEach {
                delay(100)
            }.take(6).toList()
        }
        val c2 = async {
            activeFlow.flow.onEach {
                delay(200)
            }.take(6).toList()
        }
        // ensure first flow finishes
        delay(10_000)
        // add another
        val c3 = activeFlow.flow.take(1).toList()
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
        val activeFlow = createMulticaster(flow {
            val id = createdCount++
            listOf("a", "b", "c").forEach {
                emit("$it$id")
            }
        }.onStart {
            // make sure both registers on time so that no one drops a value
            delay(100)
        })
        val c1 = async {
            activeFlow.flow.onEach {
                delay(100)
            }.take(4).toList()
        }
        val c2 = async {
            activeFlow.flow.onEach {
                delay(200)
            }.take(5).toList()
        }
        // ensure first flow finishes
        delay(10_000)
        // add another
        val c3 = activeFlow.flow.take(3).toList()
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
        val activeFlow = createMulticaster(flow {
            val id = createdCount++
            listOf("a", "b", "c").forEach {
                emit("$it$id")
            }
        }.transform {
            // make sure both registers on time so that no one drops a value
            delay(1_000)
            emit(it)
        })
        val c1 = async {
            activeFlow.flow.onEach {
                delay(100)
            }.take(4).toList()
        }
        val c2 = async {
            activeFlow.flow.onEach {
                delay(200)
            }.take(5).toList()
        }
        // ensure first flow finishes
        delay(10_000)
        // add another
        val c3 = activeFlow.flow.take(1).toList()
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
