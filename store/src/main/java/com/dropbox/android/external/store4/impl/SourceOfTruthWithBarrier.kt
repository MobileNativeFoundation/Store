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
package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.impl.operators.mapIndexed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicLong

/**
 * Wraps a [SourceOfTruth] and blocks reads while a write is in progress.
 */
@FlowPreview
@ExperimentalCoroutinesApi
internal class SourceOfTruthWithBarrier<Key, Input, Output>(
    private val delegate: SourceOfTruth<Key, Input, Output>
) {
    /**
     * Each key has a barrier so that we can block reads while writing.
     */
    private val barriers = RefCountedResource<Key, ConflatedBroadcastChannel<BarrierMsg>>(
        create = {
            ConflatedBroadcastChannel(BarrierMsg.Open.INITIAL)
        }
    )
    /**
     * Each message gets dispatched with a version. This ensures we won't accidentally turn on the
     * reader flow for a new reader that happens to have arrived while a write is in progress since
     * that write should be considered as a disk read for that flow, not fetcher.
     */
    private val versionCounter = AtomicLong(0)

    val defaultOrigin: ResponseOrigin
        get() = delegate.defaultOrigin

    fun reader(key: Key, lock: CompletableDeferred<Unit>): Flow<DataWithOrigin<Output>> {
        return flow {
            val barrier = barriers.acquire(key)
            val readerVersion: Long = versionCounter.incrementAndGet()
            try {
                lock.await()
                emitAll(barrier.asFlow()
                    .flatMapLatest {
                        val messageArrivedAfterMe = readerVersion < it.version
                        when (it) {
                            is BarrierMsg.Open -> delegate.reader(key).mapIndexed { index, output ->
                                if (index == 0 && messageArrivedAfterMe) {
                                    DataWithOrigin(
                                        origin = ResponseOrigin.Fetcher,
                                        value = output
                                    )
                                } else {
                                    DataWithOrigin(
                                        origin = delegate.defaultOrigin,
                                        value = output
                                    )
                                }
                            }
                            is BarrierMsg.Blocked -> {
                                flowOf()
                            }
                        }
                    })
            } finally {
                // we are using a finally here instead of onCompletion as there might be a
                // possibility where flow gets cancelled right before `emitAll`.
                barriers.release(key, barrier)
            }
        }
    }

    suspend fun write(key: Key, value: Input) {
        val barrier = barriers.acquire(key)
        try {
            barrier.send(BarrierMsg.Blocked(versionCounter.incrementAndGet()))
            delegate.write(key, value)
            barrier.send(BarrierMsg.Open(versionCounter.incrementAndGet()))
        } finally {
            barriers.release(key, barrier)
        }
    }

    suspend fun delete(key: Key) {
        delegate.delete(key)
    }

    private sealed class BarrierMsg(
        val version: Long
    ) {
        class Blocked(version: Long) : BarrierMsg(version)
        class Open(version: Long) : BarrierMsg(version) {
            companion object {
                val INITIAL = Open(INITIAL_VERSION)
            }
        }
    }

    // visible for testing
    internal suspend fun barrierCount() = barriers.size()

    companion object {
        private const val INITIAL_VERSION = -1L
    }
}

internal data class DataWithOrigin<T>(
    val origin: ResponseOrigin,
    val value: T?
)
