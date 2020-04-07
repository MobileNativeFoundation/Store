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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScope
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStdlibApi::class)
class SharedFlowProducerTest {
    private val scope = TestCoroutineScope()
    private val upstreamMessages = mutableListOf<String>()
    private fun createProducer(flow: Flow<String>) = SharedFlowProducer<String>(
        scope = scope,
        src = flow,
        sendUpsteamMessage = {
            if (it is ChannelManager.Message.Dispatch.Value) {
                upstreamMessages.add(it.value)
                it.delivered.complete(Unit)
            }
        }
    )

    @Test
    fun `Closing producer before it starts should not crash or consume flow`() {
        val producer = createProducer(flowOf("a", "b", "c"))
        scope.pauseDispatcher()
        producer.start()
        producer.cancel()
        assertTrue(upstreamMessages.isEmpty())
    }

    @Test
    fun `Producer forwards all values from source when acked`() {
        val producer = createProducer(flowOf("a", "b", "c"))
        assertTrue(upstreamMessages.isEmpty())
        producer.start()
        assertEquals(listOf("a", "b", "c"), upstreamMessages)
    }

    @Test
    fun `Calling start should be idempotent`() {
        val producer = createProducer(flowOf("a", "b", "c"))
        assertTrue(upstreamMessages.isEmpty())
        producer.start()
        producer.start()
        producer.start()
        assertEquals(listOf("a", "b", "c"), upstreamMessages)
    }
}
