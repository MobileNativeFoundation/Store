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
package org.mobilenativefoundation.store.store5.impl

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import org.mobilenativefoundation.store.store5.Converter
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin
import org.mobilenativefoundation.store.store5.impl.operators.mapIndexed

/**
 * Wraps a [SourceOfTruth] and blocks reads while a write is in progress.
 *
 * Used in the [RealStore] implementation to avoid
 * dispatching values to downstream while a write is in progress.
 */
@Suppress("UNCHECKED_CAST")
internal class SourceOfTruthWithBarrier<Key : Any, Network : Any, Output : Any, Local : Any>(
    private val delegate: SourceOfTruth<Key, Local>,
    private val converter: Converter<Network, Output, Local>? = null,
) {
    /**
     * Each key has a barrier so that we can block reads while writing.
     */
    private val barriers = RefCountedResource<Key, MutableStateFlow<BarrierMsg>>(
        create = {
            MutableStateFlow(BarrierMsg.Open.INITIAL)
        }
    )

    /**
     * Each message gets dispatched with a version. This ensures we won't accidentally turn on the
     * reader flow for a new reader that happens to have arrived while a write is in progress since
     * that write should be considered as a disk read for that flow, not fetcher.
     */
    private val versionCounter = atomic(0L)

    fun reader(key: Key, lock: CompletableDeferred<Unit>): Flow<StoreReadResponse<Output?>> {
        return flow {
            val barrier = barriers.acquire(key)
            val readerVersion: Long = versionCounter.incrementAndGet()
            try {
                lock.await()
                emitAll(
                    barrier
                        .flatMapLatest { barrierMessage ->
                            val messageArrivedAfterMe = readerVersion < barrierMessage.version
                            val writeError = if (messageArrivedAfterMe && barrierMessage is BarrierMsg.Open) {
                                barrierMessage.writeError
                            } else {
                                null
                            }
                            val readFlow: Flow<StoreReadResponse<Output?>> = when (barrierMessage) {
                                is BarrierMsg.Open ->
                                    delegate.reader(key).mapIndexed { index, local ->
                                        if (index == 0 && messageArrivedAfterMe) {
                                            val firstMsgOrigin = if (writeError == null) {
                                                // restarted barrier without an error means write succeeded
                                                StoreReadResponseOrigin.Fetcher
                                            } else {
                                                // when a write fails, we still get a new reader because
                                                // we've disabled the previous reader before starting the
                                                // write operation. But since write has failed, we should
                                                // use the SourceOfTruth as the origin
                                                StoreReadResponseOrigin.SourceOfTruth
                                            }

                                            val output = when {
                                                local != null -> converter?.fromLocalToOutput(local) ?: local as? Output
                                                else -> null
                                            }

                                            StoreReadResponse.Data(
                                                origin = firstMsgOrigin,
                                                value = output
                                            )
                                        } else {
                                            val output = when {
                                                local != null -> converter?.fromLocalToOutput(local) ?: local as? Output
                                                else -> null
                                            }

                                            StoreReadResponse.Data(
                                                origin = StoreReadResponseOrigin.SourceOfTruth,
                                                value = output
                                            ) as StoreReadResponse<Output?>
                                        }
                                    }.catch { throwable ->
                                        this.emit(
                                            StoreReadResponse.Error.Exception(
                                                error = SourceOfTruth.ReadException(
                                                    key = key,
                                                    cause = throwable.cause ?: throwable
                                                ),
                                                origin = StoreReadResponseOrigin.SourceOfTruth
                                            )
                                        )
                                    }

                                is BarrierMsg.Blocked -> {
                                    flowOf()
                                }
                            }
                            readFlow
                                .onStart {
                                    // if we have a pending error, make sure to dispatch it first.
                                    if (writeError != null) {
                                        emit(
                                            StoreReadResponse.Error.Exception(
                                                origin = StoreReadResponseOrigin.SourceOfTruth,
                                                error = writeError
                                            )
                                        )
                                    }
                                }
                        }
                )
            } finally {
                // we are using a finally here instead of onCompletion as there might be a
                // possibility where flow gets cancelled right before `emitAll`.
                barriers.release(key, barrier)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun write(key: Key, value: Output) {
        val barrier = barriers.acquire(key)
        try {
            barrier.emit(BarrierMsg.Blocked(versionCounter.incrementAndGet()))
            val writeError = try {
                val local = converter?.fromOutputToLocal(value)
                val input = local ?: value
                delegate.write(key, input as Local)
                null
            } catch (throwable: Throwable) {
                if (throwable !is CancellationException) {
                    throwable
                } else {
                    null
                }
            }

            barrier.emit(
                BarrierMsg.Open(
                    version = versionCounter.incrementAndGet(),
                    writeError = writeError?.let {
                        SourceOfTruth.WriteException(
                            key = key,
                            value = value,
                            cause = writeError
                        )
                    }
                )
            )
            if (writeError is CancellationException) {
                // only throw if it failed because of cancelation.
                // otherwise, we take care of letting downstream know that there was a write error
                throw writeError
            }
        } finally {
            barriers.release(key, barrier)
        }
    }

    suspend fun delete(key: Key) {
        delegate.delete(key)
    }

    suspend fun deleteAll() {
        delegate.deleteAll()
    }

    private sealed class BarrierMsg(
        val version: Long
    ) {
        class Blocked(version: Long) : BarrierMsg(version)
        class Open(version: Long, val writeError: Throwable? = null) : BarrierMsg(version) {
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
