package com.nytimes.android.external.store3.pipeline

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

private object NotReceived

// this is actually in the library, not in our version yet or not released
@FlowPreview
internal suspend fun <T> Flow<T>.singleOrNull(): T? {
    var value: Any? = NotReceived
    try {
        collect {
            value = it
            throw AbortFlowException()
        }
    } catch (abort: AbortFlowException) {
        // expected
    }
    return if (value === NotReceived) {
        null
    } else {
        @Suppress("UNCHECKED_CAST")
        value as? T
    }
}

@FlowPreview
internal fun <T, R> Flow<T>.sideCollect(
        other: Flow<R>,
        otherCollect: suspend (R) -> Unit
) = flow {
    coroutineScope {
        val sideJob = launch {
            other.collect {
                otherCollect(it)
            }
        }
        this@sideCollect.collect {
            emit(it)
        }
        // when main flow ends, cancel the side channel.
        sideJob.cancelAndJoin()
    }
}