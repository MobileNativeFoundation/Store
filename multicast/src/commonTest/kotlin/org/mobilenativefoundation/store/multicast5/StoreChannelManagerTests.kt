package org.mobilenativefoundation.store.multicast5

import app.cash.turbine.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest

class StoreChannelManagerTests {
  @Test
  fun cancelledDownstreamChannelShouldNotCancelOtherChannels() = runTest {
    val coroutineScope = CoroutineScope(Dispatchers.Default)
    val lockUpstream = Mutex(true)
    val testMessages = listOf(1, 2, 3)
    val numChannels = 20
    val upstreamFlow = flow { lockUpstream.withLock { testMessages.onEach { emit(it) } } }
    val channelManager =
      StoreChannelManager(
        scope = coroutineScope,
        bufferSize = 0,
        upstream = upstreamFlow,
        piggybackingDownstream = false,
        keepUpstreamAlive = false,
        onEach = {},
      )
    val channels = createChannels(numChannels)
    val channelToBeCancelled =
      Channel<ChannelManager.Message.Dispatch<Int>>(Channel.UNLIMITED).also { channel ->
        coroutineScope.launch { channel.consumeAsFlow().test { cancelAndIgnoreRemainingEvents() } }
      }
    coroutineScope.launch {
      channels.forEach { channelManager.addDownstream(it) }
      lockUpstream.unlock()
    }
    coroutineScope.launch { channelManager.addDownstream(channelToBeCancelled) }

    channels.forEach { channel ->
      val messagesFlow =
        channel
          .consumeAsFlow()
          .filterIsInstance<ChannelManager.Message.Dispatch.Value<Int>>()
          .onEach { it.delivered.complete(Unit) }

      messagesFlow.test {
        for (message in testMessages) {
          val dispatchValue = awaitItem()
          assertEquals(message, dispatchValue.value)
        }
        awaitComplete()
      }
    }
  }

  private fun createChannels(count: Int): List<Channel<ChannelManager.Message.Dispatch<Int>>> {
    return (1..count).map { Channel(Channel.UNLIMITED) }
  }
}
