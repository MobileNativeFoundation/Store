package com.nytimes.android.external.store4.impl.operators

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flow

@ExperimentalCoroutinesApi
internal inline fun <T, R> Flow<T>.mapIndexed(crossinline block: (Int, T) -> R) = flow {
    this@mapIndexed.collectIndexed { index, value ->
        emit(block(index, value))
    }
}