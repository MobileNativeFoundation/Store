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
import kotlinx.coroutines.FlowPreview
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
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@FlowPreview
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
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
            assertThat(collection.await()).isEqualTo(listOf("a", "b"))
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
            assertThat(collection.isCompleted).isTrue()
            assertThat(collection.getCompleted()).isEmpty()
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

            assertThat(collection1.await()).isEqualTo(listOf("a", "b"))
            assertThat(collection2.await()).isEqualTo(listOf("a", "b"))
        }

    @Test
    fun `GIVEN no keepUpstreamAlive WHEN add two non overlapping downstreams THEN second channel receives value AND registers only once`() =
        scope.runBlockingTest {
            val upstreamCreateCount =
                `consume two non-overlapping downstreams, verify emissions and count upstream creations`(
                    keepUpstreamAlive = false,
                    emitDuringKeepalive = false
                )
            assertThat(upstreamCreateCount).isEqualTo(2)
        }

    @Test
    fun `GIVEN keepUpstreamAlive WHEN add two non overlapping downstreams THEN second channel receives value AND registers only once`() =
        scope.runBlockingTest {
            val upstreamCreateCount =
                `consume two non-overlapping downstreams, verify emissions and count upstream creations`(
                    keepUpstreamAlive = true,
                    emitDuringKeepalive = false
                )
            assertThat(upstreamCreateCount).isEqualTo(1)
        }

    @Test
    fun `GIVEN keepUpstreamAlive AND no buffer WHEN add two non overlapping downstreams AND emit during keepalive THEN second channel receives one value`() =
        scope.runBlockingTest {
            `consume two non-overlapping downstreams, verify emissions and count upstream creations`(
                    keepUpstreamAlive = true,
                    emitDuringKeepalive = true
                )
        }

    @Test
    fun `GIVEN keepUpstreamAlive AND buffer of size 1 WHEN add two non overlapping downstreams AND emit during keepalive THEN second channel receives 2 values`() =
        scope.runBlockingTest {
            `consume two non-overlapping downstreams, verify emissions and count upstream creations`(
                keepUpstreamAlive = true,
                emitDuringKeepalive = true,
                bufferSize = 1
            )
        }

    private suspend fun `consume two non-overlapping downstreams, verify emissions and count upstream creations`(
        keepUpstreamAlive: Boolean,
        emitDuringKeepalive: Boolean,
        bufferSize: Int = 0
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
            bufferSize,
            onEach = {},
            keepUpstreamAlive = keepUpstreamAlive,
            upstream = upstream
        )

        // subscribe with fist downstream
        val downstream1 =
            Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
        manager.addDownstream(downstream1)
        val s1 = async {
            try {
                downstream1.consumeAsFlow()
                    .first().let {
                        it.markDelivered()
                        it.value
                    }
            } finally {
                manager.removeDownstream(downstream1)
            }
        }

        // get value and make sure first downstream is closed
        upstreamChannel.send("a")
        assertThat(s1.await()).isEqualTo("a")
        assertThat(downstream1.isClosedForReceive)

        val expected = mutableListOf<String>()

        // optentailly emit with no downstreams
        if (emitDuringKeepalive) {
            upstreamChannel.send("b")
            expected += "b"
        }
        // add second downstream
        val downstream2 =
            Channel<Dispatch.Value<String>>(Channel.UNLIMITED)
        manager.addDownstream(downstream2)
        val s2 = async {
            try {
                downstream2.consumeAsFlow()
                    .onEach { it.markDelivered() }
                    .toList().map { it.value }
            } finally {
                manager.removeDownstream(downstream2)
            }
        }

        // get the final value
        upstreamChannel.send("c")
        expected += "c"
        upstreamChannel.close()
        assertThat(s2.await()).isEqualTo(expected)

        return@coroutineScope upstreamCreateCount
    }
}

private class TestException : Exception()
