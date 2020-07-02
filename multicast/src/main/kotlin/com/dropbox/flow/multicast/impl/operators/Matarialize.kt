package com.dropbox.flow.multicast.impl.operators

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion

sealed class Notification<T> {
    class Value<T>(val value: T): Notification<T>()
    class Error<T>(val error: Throwable): Notification<T>()
    class Close<T> : Notification<T>()
}

/**
 * Naive implementation of materialize.
 */
@ExperimentalCoroutinesApi
fun <T> Flow<T>.materialize() : Flow<Notification<T>> = this
    .map { Notification.Value(it) as Notification<T> }
    .catch { Notification.Error<T>(it) }
    .onCompletion { emit(Notification.Close<T>()) }
