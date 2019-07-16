package com.nytimes.android.external.store3.pipeline

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

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