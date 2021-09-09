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

import com.dropbox.flow.multicast.ChannelManager.Message.Dispatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@ExperimentalCoroutinesApi
class ChannelManagerTest {
    private val scope = TestCoroutineScope()
    private val upstream: Channel<String> = Channel(Channel.UNLIMITED)
    private val manager = ChannelManager(
        scope,
        0,
        onEach = {},
        upstream = upstream.consumeAsFlow()
    )

    @Test
    fun `GIVEN one downstream WHEN two values come in on the upstream THEN two values are consumed`() =
        scope.runBlockingTest {
            val collection = async {
                val downstream = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
                manager.addDownstream(downstream)
                downstream.consumeAsFlow()
                    .onEach { it.markDelivered() }
                    .take(2)
                    .onCompletion { manager.removeDownstream(downstream) }
                    .toList()
                    .map { it.value }
            }
            upstream.send("a")
            upstream.send("b")
            upstream.close()
            assertEquals(listOf("a", "b"), collection.await())
        }

    @Test(expected = TestException::class)
    fun `GIVEN one downstream WHEN upstream errors THEN error is propagated`() =
        scope.runBlockingTest {
            val downstream = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
            manager.addDownstream(downstream)

            val collection = async {
                downstream.consumeAsFlow()
                    .onEach { it.markDelivered() }
                    .take(2)
                    .onCompletion { manager.removeDownstream(downstream) }
                    .toList()
                    .map { it.value }
            }
            upstream.close(TestException())
            collection.await()
            fail("collection should propagate upstream exception.")
        }

    @Test
    fun `GIVEN one downstream WHEN upstream closes THEN downstream is closed`() =
        scope.runBlockingTest {
            val downstream = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
            manager.addDownstream(downstream)

            val collection = async {
                downstream.consumeAsFlow()
                    .onEach { it.markDelivered() }
                    .onCompletion { manager.removeDownstream(downstream) }
                    .toList()
                    .map { it.value }
            }
            upstream.close()
            // give the upstream a chance to finish and check that downstream finished.
            // does not await on downstream to avoid the test hanging in case of a bug.
            delay(100)
            assertTrue(collection.isCompleted)
            assertTrue(collection.getCompleted().isEmpty())
        }

    @Test
    fun `GIVEN two downstreams WHEN two values come in on the upstream THEN two values are consumed`() =
        scope.runBlockingTest {
            val downstream1 = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
            val downstream2 = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)

            // ack on channel 1
            val collection1 = async {
                manager.addDownstream(downstream1)
                manager.addDownstream(downstream2)
                downstream1.consumeAsFlow()
                    .onEach { it.markDelivered() }
                    .take(2)
                    .onCompletion { manager.removeDownstream(downstream1) }
                    .toList()
                    .map { it.value }
            }

            // also consume (without ack) on channel 2 to make sure we got everything.
            val collection2 = async {
                downstream2.consumeAsFlow()
                    .take(2)
                    .onCompletion { manager.removeDownstream(downstream2) }
                    .toList()
                    .map { it.value }
            }

            upstream.send("a")
            upstream.send("b")
            upstream.close()

            assertEquals(listOf("a", "b"), collection1.await())
            assertEquals(listOf("a", "b"), collection2.await())
        }

    @Test
    fun `GIVEN no keepUpstreamAlive WHEN add two non overlapping downstreams THEN second channel receives value AND registers only once`() =
        scope.runBlockingTest {
            val upstreamCreateCount =
                `consume two non-overlapping downstreams and count upstream creations`(
                    keepUpstreamAlive = false
                )
            assertEquals(2, upstreamCreateCount)
        }

    @Test
    fun `GIVEN keepUpstreamAlive WHEN add two non overlapping downstreams THEN second channel receives value AND registers only once`() =
        scope.runBlockingTest {
            val upstreamCreateCount =
                `consume two non-overlapping downstreams and count upstream creations`(
                    keepUpstreamAlive = true
                )
            assertEquals(1, upstreamCreateCount)
        }

    private suspend fun `consume two non-overlapping downstreams and count upstream creations`(
        keepUpstreamAlive: Boolean
    ) = coroutineScope {
        // upstream that tracks creates and can be emitted to on demand
        var upstreamCreateCount = 0
        val upstreamChannel = Channel<String>(Channel.UNLIMITED)
        val upstream = flow {
            upstreamCreateCount++
            for (message in upstreamChannel) {
                emit(message)
            }
        }

        // create a manager with this specific upstream
        val manager = ChannelManager(
            scope,
            bufferSize = 1,
            onEach = {},
            keepUpstreamAlive = keepUpstreamAlive,
            upstream = upstream
        )

        // subscribe with fist downstream
        val downstream1 =
            Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
        manager.addDownstream(downstream1)
        val s1 = async {
            downstream1.consumeAsFlow()
                .onCompletion { manager.removeDownstream(downstream1) }
                .first().let {
                    it.markDelivered()
                    it.value
                }
        }

        // get value and make sure first downstream is closed
        upstreamChannel.send("a")
        assertEquals("a", s1.await())
        assertTrue(downstream1.isClosedForReceive)

        val downstream2 =
            Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
        manager.addDownstream(downstream2)
        val s2 = async {
            downstream2.consumeAsFlow()
                .onCompletion { manager.removeDownstream(downstream2) }
                .onEach { it.markDelivered() }
                .toList()
                .map { it.value }
        }

        // get the final value
        upstreamChannel.send("b")
        upstreamChannel.close()
        assertEquals(listOf("a", "b"), s2.await()) // buffer=1 so 'a' should be sent as well

        return@coroutineScope upstreamCreateCount
    }

    @Test
    fun `GIVEN keepUpstreamAlive AND buffer of size 1 WHEN add two non overlapping downstreams AND emit during keepalive THEN second channel receives 2 values`() =
        scope.runBlockingTest {
            val manager = newManagerInKeepAliveModeWithPendingFetch(
                bufferSize = 1,
                firstValue = "a",
                pendingValue = "b"
            )

            // add downstream
            val downstream = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
            manager.addDownstream(downstream)
            val s2 = async {
                downstream.consumeAsFlow()
                    .onCompletion { manager.removeDownstream(downstream) }
                    .onEach { it.markDelivered() }
                    .toList()
                    .map { it.value }
            }
            upstream.send("c")
            upstream.close()

            assertEquals(listOf("b", "c"), s2.await())
        }

    @Test
    fun `GIVEN keepUpstreamAlive AND buffer of size 1 WHEN add two non overlapping downstreams AND a 3rd channel overlapping the 2nd channle AND emit during keepalive THEN 3rd channel receives 1 values`() =
        scope.runBlockingTest {
            val manager = newManagerInKeepAliveModeWithPendingFetch(
                bufferSize = 1,
                firstValue = "a",
                pendingValue = "b"
            )

            // add downstream
            val downstream2 = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
            manager.addDownstream(downstream2)
            val pending = async {
                downstream2.receive().let {
                    it.markDelivered()
                    it.value
                }
            }
            assertEquals("b", pending.await())

            val downstream3 = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
            manager.addDownstream(downstream3)
            val collection = async {
                downstream3.consumeAsFlow()
                    .onEach { it.markDelivered() }
                    .toList()
                    .map { it.value }
            }
            upstream.close()

            assertEquals(listOf("b"), collection.await())
        }

    @Test
    fun `GIVEN keepUpstreamAlive AND buffer of size 1 WHEN add two non overlapping downstreams AND emit during keepalive AND emit after keepalive THEN second channel receives 2 values`() =
        scope.runBlockingTest {
            val manager = newManagerInKeepAliveModeWithPendingFetch(
                bufferSize = 1,
                pendingValue = "b"
            )

            // add downstream
            val downstream = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
            manager.addDownstream(downstream)
            val s2 = async {
                downstream.consumeAsFlow()
                    .onCompletion { manager.removeDownstream(downstream) }
                    .onEach { it.markDelivered() }
                    .toList()
                    .map { it.value }
            }

            // get the final value
            upstream.send("c")
            upstream.close()
            assertEquals(listOf("b", "c"), s2.await())
        }

    @Test
    fun `GIVEN keepUpstreamAlive AND buffer of size 2 WHEN add two non overlapping downstreams AND emit during keepalive AND emit after keepalive THEN second channel receives 3 values`() =
        scope.runBlockingTest {
            val manager = newManagerInKeepAliveModeWithPendingFetch(
                bufferSize = 2,
                firstValue = "a",
                pendingValue = "b"
            )

            // add downstream
            val downstream = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
            manager.addDownstream(downstream)
            val collection = async {
                downstream.consumeAsFlow()
                    .onCompletion { manager.removeDownstream(downstream) }
                    .onEach { it.markDelivered() }
                    .toList()
                    .map { it.value }
            }

            // get the final value
            upstream.send("c")
            upstream.close()
            assertEquals(listOf("a", "b", "c"), collection.await())
        }

    private suspend fun newManagerInKeepAliveModeWithPendingFetch(
        bufferSize: Int,
        firstValue: String = "a",
        pendingValue: String = "b"
    ) = coroutineScope {
        // upstream that tracks creates and can be emitted to on demand

        // create a manager with this specific upstream
        val manager = ChannelManager(
            scope,
            bufferSize,
            onEach = {},
            keepUpstreamAlive = true,
            upstream = upstream.consumeAsFlow()
        )

        // subscribe with fist downstream
        val downstream =
            Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
        manager.addDownstream(downstream)
        val value = async {
            downstream.consumeAsFlow()
                .onCompletion { manager.removeDownstream(downstream) }
                .first().let {
                    it.markDelivered()
                    it.value
                }
        }

        // get value and make sure first downstream is closed
        upstream.send(firstValue)
        assertEquals(firstValue, value.await())
        assertTrue(downstream.isClosedForReceive)

        // emit with no downstreams
        upstream.send(pendingValue)

        return@coroutineScope manager
    }

    @Test
    fun `GIVEN two downstreams and a dispatched value WHEN ChannelManager is closed THEN it should close downstreams`() =
        assertClosingChannelManager(true)

    @Test
    fun `GIVEN two downstreams without a dispatched value WHEN ChannelManager is closed THEN it should close downstreams`() =
        assertClosingChannelManager(false)

    private fun assertClosingChannelManager(dispatchValue: Boolean) = scope.runBlockingTest {
        val downstream1 = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
        val downstream2 = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
        manager.addDownstream(downstream1)
        manager.addDownstream(downstream2)
        if (dispatchValue) {
            upstream.send("a")
        }
        manager.close()
        assertTrue(downstream1.isClosedForSend)
        assertTrue(downstream2.isClosedForSend)
        // it can be open for receive if and only if we've already sent a value
        assertEquals(!dispatchValue, downstream1.isClosedForReceive)
        assertEquals(!dispatchValue, downstream2.isClosedForReceive)
    }

    @Test
    fun `Calling close multiple times on ChannelManager should be idempotent`() =
        scope.runBlockingTest {
            val downstream = Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
            manager.addDownstream(downstream)
            manager.close()
            manager.close()
            assertTrue(downstream.isClosedForSend)
        }
}

private class TestException : Exception()
