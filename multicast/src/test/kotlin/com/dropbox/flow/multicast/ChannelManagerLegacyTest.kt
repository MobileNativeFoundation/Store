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

import com.dropbox.flow.multicast.ChannelManager.Message.Dispatch.Value
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@FlowPreview
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class ChannelManagerLegacyTest {
    private val scope = TestCoroutineScope()
    private val manager = ChannelManager<String>(
        scope,
        0,
        onEach = {},
        upstream = flow {
            suspendCancellableCoroutine<String> {
                // never end
            }
        }
    )
    @Test
    fun simple() = scope.runBlockingTest {
        val collection = async {
            val channel = Channel<Value<String>>(Channel.UNLIMITED)
            try {
                manager.addDownstream(channel)
                channel.consumeAsFlow().take(2).toList()
                    .map { it.value }
            } finally {
                manager.removeDownstream(channel)
            }
        }
        val ack1 = CompletableDeferred<Unit>()
        manager.send(Value("a", ack1))

        val ack2 = CompletableDeferred<Unit>()
        manager.send(Value("b", ack2))
        assertThat(collection.await()).isEqualTo(listOf("a", "b"))
    }
}
