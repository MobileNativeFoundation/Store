package com.nytimes.android.external.store4.impl.multicast

import com.nytimes.android.external.store4.impl.multicast.ChannelManager.Message.AddChannel
import com.nytimes.android.external.store4.impl.multicast.ChannelManager.Message.DispatchValue
import com.nytimes.android.external.store4.impl.multicast.ChannelManager.Message.RemoveChannel
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
