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
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Multicaster tests where downstream is not closed even when upstream is closed.
 * It basically waits until there is another reason to enable upstream and will receive those
 * values as well.
 */
@ExperimentalCoroutinesApi
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
            }
        )
    }

    @Test
    fun piggyback() = testScope.runBlockingTest {
        var createdCount = 0
        val activeFlow = createMulticaster(
            flow {
                val id = createdCount++
                listOf("a", "b", "c").forEach {
                    emit("$it$id")
                }
            }.onStart {
                // make sure both registers on time so that no one drops a value
                delay(100)
            }
        )
        val c1 = async {
            activeFlow.newDownstream().onEach {
                delay(100)
            }.take(6).toList()
        }
        val c2 = async {
            activeFlow.newDownstream().onEach {
                delay(200)
            }.take(6).toList()
        }
        // ensure first flow finishes
        delay(10_000)
        // add another
        val c3 = activeFlow.newDownstream().take(3).toList()
        assertEquals(listOf("a0", "b0", "c0", "a1", "b1", "c1"), c1.await())
        assertEquals(listOf("a0", "b0", "c0", "a1", "b1", "c1"), c2.await())
        assertEquals(listOf("a1", "b1", "c1"), c3)
        assertEquals(2, createdCount)
    }

    @Test
    fun piggyback_newStreamClosesEarly() = testScope.runBlockingTest {
        var createdCount = 0
        val activeFlow = createMulticaster(
            flow {
                val id = createdCount++
                listOf("a", "b", "c").forEach {
                    emit("$it$id")
                }
            }.onStart {
                // make sure both registers on time so that no one drops a value
                delay(100)
            }
        )
        val c1 = async {
            activeFlow.newDownstream().onEach {
                delay(100)
            }.take(6).toList()
        }
        val c2 = async {
            activeFlow.newDownstream().onEach {
                delay(200)
            }.take(6).toList()
        }
        // ensure first flow finishes
        delay(10_000)
        // add another
        val c3 = activeFlow.newDownstream().take(1).toList()
        assertEquals(listOf("a0", "b0", "c0", "a1", "b1", "c1"), c1.await())
        assertEquals(listOf("a0", "b0", "c0", "a1", "b1", "c1"), c2.await())
        assertEquals(listOf("a1"), c3)
        assertEquals(2, createdCount)
    }

    @Test
    fun piggyback_oldStreamClosesEarly() = testScope.runBlockingTest {
        var createdCount = 0
        val activeFlow = createMulticaster(
            flow {
                val id = createdCount++
                listOf("a", "b", "c").forEach {
                    emit("$it$id")
                }
            }.onStart {
                // make sure both registers on time so that no one drops a value
                delay(100)
            }
        )
        val c1 = async {
            activeFlow.newDownstream().onEach {
                delay(100)
            }.take(4).toList()
        }
        val c2 = async {
            activeFlow.newDownstream().onEach {
                delay(200)
            }.take(5).toList()
        }
        // ensure first flow finishes
        delay(10_000)
        // add another
        val c3 = activeFlow.newDownstream().take(3).toList()
        assertEquals(listOf("a0", "b0", "c0", "a1"), c1.await())
        assertEquals(listOf("a0", "b0", "c0", "a1", "b1"), c2.await())
        assertEquals(listOf("a1", "b1", "c1"), c3)
        assertEquals(2, createdCount)
    }

    @Test
    fun piggyback_allStreamsCloseSearch() = testScope.runBlockingTest {
        var createdCount = 0
        val activeFlow = createMulticaster(
            flow {
                val id = createdCount++
                listOf("a", "b", "c").forEach {
                    emit("$it$id")
                }
            }.transform {
                // make sure both registers on time so that no one drops a value
                delay(1_000)
                emit(it)
            }
        )
        val c1 = async {
            activeFlow.newDownstream().onEach {
                delay(100)
            }.take(4).toList()
        }
        val c2 = async {
            activeFlow.newDownstream().onEach {
                delay(200)
            }.take(5).toList()
        }
        // ensure first flow finishes
        delay(10_000)
        // add another
        val c3 = activeFlow.newDownstream().take(1).toList()
        assertEquals(listOf("a0", "b0", "c0", "a1"), c1.await())
        assertEquals(listOf("a0", "b0", "c0", "a1", "b1"), c2.await())
        assertEquals(listOf("a1"), c3)
        assertEquals(2, createdCount)
        // make sure we didn't keep upsteam too long
        assertEquals(listOf("a0", "b0", "c0", "a1", "b1"), dispatchLog)
    }
}
