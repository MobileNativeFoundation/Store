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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
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
import java.lang.Exception

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
    fun `Given one downstream WHEN two values come in on the upstream THEN two values are consumed`() =
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
    fun `Given one downstream WHEN upstream errors THEN error is propagated`() =
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
    fun `Given one downstream WHEN upstream closes THEN downstream is closed`() =
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
    fun `Given two downstreams WHEN two values come in on the upstream THEN two values are consumed`() =
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
}

private class TestException : Exception()
