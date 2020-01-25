/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dropbox.android.external.store4.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Only used in FlowStoreTest. We should get rid of it eventually.
 */
@ExperimentalCoroutinesApi
class SimplePersisterAsFlowable<Key, Input, Output>(
    private val reader: suspend (Key) -> Output?,
    private val writer: suspend (Key, Input) -> Unit,
    private val delete: (suspend (Key) -> Unit)? = null
) {
    private val versionTracker =
        KeyTracker<Key>()

    fun flowReader(key: Key): Flow<Output?> = flow {
        versionTracker.keyFlow(key).collect {
            emit(reader(key))
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

/**
 * helper class which provides Flows for Keys that can be tracked.
 */
@ExperimentalCoroutinesApi
internal class KeyTracker<Key> {
    private val lock = Mutex()
    // list of open key channels
    private val channels = mutableMapOf<Key, KeyChannel>()

    // for testing
    internal fun activeKeyCount() = channels.size

    /**
     * invalidates the given key. If there are flows returned from [keyFlow] for the given [key],
     * they'll receive a new emission
     */
    suspend fun invalidate(key: Key) {
        lock.withLock {
            channels[key]
        }?.channel?.send(Unit)
    }

    /**
     * Returns a Flow that emits once and then every time the given [key] is invalidated via
     * [invalidate]
     */
    suspend fun keyFlow(key: Key): Flow<Unit> {
        // it is important to allocate KeyChannel lazily (ony when the returned flow is collected
        // from). Otherwise, we might just create many of them that are never observed hence never
        // cleaned up
        return flow {
            val keyChannel = lock.withLock {
                channels.getOrPut(key) {
                    KeyChannel(
                        channel = BroadcastChannel<Unit>(Channel.CONFLATED).apply {
                            // start w/ an initial value.
                            offer(Unit)
                        }
                    )
                }.also {
                    it.acquire() // refcount
                }
            }
            try {
                emitAll(keyChannel.channel.openSubscription())
            } finally {
                lock.withLock {
                    keyChannel.release()
                    if (keyChannel.channel.isClosedForSend) {
                        channels.remove(key)
                    }
                }
            }
        }
    }

    /**
     * A data structure to count how many active flows we have on this channel
     */
    private data class KeyChannel(
        val channel: BroadcastChannel<Unit>,
        var collectors: Int = 0
    ) {
        fun acquire() {
            collectors++
        }

        fun release() {
            collectors--
            if (collectors == 0) {
                channel.close()
            }
        }
    }
}
