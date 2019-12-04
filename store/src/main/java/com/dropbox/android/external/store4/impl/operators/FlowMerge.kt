package com.dropbox.android.external.store4.impl.operators

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Merge implementation tells downstream what the source is and also uses a rendezvous channel
 */
@ExperimentalCoroutinesApi
internal fun <T, R> Flow<T>.merge(other: Flow<R>): Flow<Either<T, R>> {
    return channelFlow<Either<T, R>> {
        launch {
            this@merge.collect {
                send(Either.Left(it))

            }
        }
        launch {
            other.collect {
                send(Either.Right(it))
            }
        }
    }.buffer(Channel.RENDEZVOUS)
}

internal sealed class Either<T, R> {
    data class Left<T, R>(val value: T) : Either<T, R>()

    data class Right<T, R>(val value: R) : Either<T, R>()
}
