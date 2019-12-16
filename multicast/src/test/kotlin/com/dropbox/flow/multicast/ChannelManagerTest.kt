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

import com.dropbox.flow.multicast.ChannelManager.Message.AddChannel
import com.dropbox.flow.multicast.ChannelManager.Message.DispatchValue
import com.dropbox.flow.multicast.ChannelManager.Message.RemoveChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@FlowPreview
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class ChannelManagerTest {
    private val scope = TestCoroutineScope()
    private val manager = ChannelManager<String>(
            scope,
            0,
            onEach = {}
    ) {
        SharedFlowProducer(
                scope, src =
        flow {
            suspendCancellableCoroutine<String> {
                // never end
            }
        },
                channelManager = it
        )
    }

    @Test
    fun simple() = scope.runBlockingTest {
        val collection = async {
            val channel = Channel<DispatchValue<String>>(Channel.UNLIMITED)
            try {
                manager.send(AddChannel(channel))
                channel.consumeAsFlow().take(2).toList()
                    .map { it.value }
            } finally {
                manager.send(RemoveChannel(channel))
            }
        }
        val ack1 = CompletableDeferred<Unit>()
        manager.send(DispatchValue("a", ack1))

        val ack2 = CompletableDeferred<Unit>()
        manager.send(DispatchValue("b", ack2))
        assertThat(collection.await()).isEqualTo(listOf("a", "b"))
    }
}
