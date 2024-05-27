package org.mobilenativefoundation.store.multicast5

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StoreChannelManagerTests {

    @Test
    fun cancelledDownstreamChannelShouldNotCancelOtherChannels() {
        val messages = listOf(1, 2, 3)
        val scope = CoroutineScope(Dispatchers.Default)
        val lockUpstream = Mutex(true)
        val upstreamFlow = flow {
            lockUpstream.withLock {
                messages.onEach { emit(it) }
            }
        }
        val channelManager = StoreChannelManager(
            scope = scope,
            bufferSize = 0,
            upstream = upstreamFlow,
            piggybackingDownstream = false,
            keepUpstreamAlive = false,
            onEach = { }
        )
        val channels =
            (1..20).map { Channel<ChannelManager.Message.Dispatch<Int>>(Channel.UNLIMITED) }

        val cancelledChannel =
            Channel<ChannelManager.Message.Dispatch<Int>>(Channel.UNLIMITED).also {
                scope.launch {
                    it.consumeAsFlow().first()
                }
            }

        scope.launch {
            channels.forEach { channelManager.addDownstream(it) }
            lockUpstream.unlock()
        }
        scope.launch { channelManager.addDownstream(cancelledChannel) }

        runTest {
            channels.forEach { channel ->
                val messagesFlow = channel.consumeAsFlow()
                    .filterIsInstance<ChannelManager.Message.Dispatch.Value<Int>>()
                    .onEach { it.delivered.complete(Unit) }
                    .map { it.value }

                assertEquals(
                    messages,
                    messagesFlow.take(3).toList(),
                )
            }
        }
        scope.cancel()
    }
}
