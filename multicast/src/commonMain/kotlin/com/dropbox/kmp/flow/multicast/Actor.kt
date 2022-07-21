package com.dropbox.kmp.flow.multicast

import kotlinx.coroutines.CompletionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/*
 * Credits to nickallendev
 * https://discuss.kotlinlang.org/t/actor-kotlin-common/19569
 */
internal fun <E> CoroutineScope.actor(
    context: CoroutineContext = EmptyCoroutineContext,
    capacity: Int = 0,
    onCompletion: CompletionHandler? = null,
    block: suspend CoroutineScope.(ReceiveChannel<E>) -> Unit
): SendChannel<E> {
    val channel = Channel<E>(capacity)
    val job = launch(context) {
        try {
            block(channel)
        } finally {
            if (isActive) channel.cancel()
        }
    }
    if (onCompletion != null) job.invokeOnCompletion(handler = onCompletion)
    return channel
}
