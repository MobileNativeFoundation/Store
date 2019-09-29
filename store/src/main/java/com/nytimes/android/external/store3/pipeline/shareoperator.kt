package com.nytimes.android.external.store3.pipeline

/*
 * Copyright 2019 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.broadcastIn
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Shares the source flow between all downstream collectors.
 * The flow is collected when the first downstream collector starts, and cancelled after the last
 * downstream collector is cancelled. To control the dispatcher or other context elements of the
 * coroutine on which this flow is collected, use the `flowOn` operator before calling `share`.
 * To control buffering, use the `buffer` operator before calling `share`.
 *
 * Should be removed once the
 * [official share operator](https://github.com/Kotlin/kotlinx.coroutines/issues/1261) lands.
 */
@UseExperimental(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal fun <T> Flow<T>.share(): Flow<T> {
    var currentChannel: BroadcastChannel<T>? = null
    var refCount = 0
    val lock = Mutex()

    fun createChannel(): BroadcastChannel<T> = broadcastIn(CoroutineScope(EmptyCoroutineContext))

    suspend fun incRefCount() = lock.withLock {
        if (refCount == 0) {
            currentChannel = createChannel()
        }
        refCount++
    }

    suspend fun decRefCount() = lock.withLock {
        refCount--
        if (refCount == 0) {
            currentChannel!!.cancel()
            currentChannel = null
        }
    }

    return flow {
        incRefCount()
        try {
            emitAll(currentChannel!!.asFlow())
        } finally {
            decRefCount()
        }
    }
}