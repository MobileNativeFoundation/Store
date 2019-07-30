package com.nytimes.android.external.store3.pipeline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.drop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
@FlowPreview
class SimplePersisterAsFlowable<Key, Input, Output>(
    private val reader: suspend (Key) -> Output,
    private val writer: suspend (Key, Input) -> Unit,
    private val delete: (suspend (Key) -> Unit)? = null
) {
    private val versionTracker = KeyTracker<Key>()

    fun flowReader(key: Key): Flow<Output> {
        return flow {
            versionTracker.keyFlow(key).collect {
                emit(reader(key))
            }
        }
    }

    suspend fun flowWriter(key: Key, input: Input) {
        writer(key, input)
        versionTracker.invalidate(key)
    }

    suspend fun flowDelete(key: Key) {
        delete?.let {
            it(key)
            versionTracker.invalidate(key)
        }
    }
}

@FlowPreview
@ExperimentalCoroutinesApi
private class KeyTracker<Key> {
    private val mutex = Mutex()
    private val invalidations = BroadcastChannel<Pair<Key, Unit>?>(Channel.CONFLATED).apply {
        //a conflated channel always maintains the last element, the stream method ignore this element.
        //Here we add an empty element that will be ignored later
        offer(null)
    }

    suspend fun invalidate(key: Key) = mutex.withLock {
        invalidations.send(key to Unit)
    }

    @ObsoleteCoroutinesApi
    suspend fun keyFlow(key: Key): Flow<Unit> = flow {
        emit(Unit)
        invalidations.openSubscription().drop(1).consumeEach {
            if (it != null && it.first == key) {
                emit(it.second)
            }
        }
    }
}