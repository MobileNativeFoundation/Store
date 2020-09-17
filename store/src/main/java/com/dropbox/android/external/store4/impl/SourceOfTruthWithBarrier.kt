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
import com.dropbox.android.external.store4.SourceOfTruth
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.android.external.store4.impl.operators.Either
import com.dropbox.android.external.store4.impl.operators.mapIndexed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import java.util.concurrent.atomic.AtomicLong

/**
 * Wraps a [SourceOfTruth] and blocks reads while a write is in progress.
 *
 * Used in the [com.dropbox.android.external.store4.impl.RealStore] implementation to avoid
 * dispatching values to downstream while a write is in progress.
 */
@FlowPreview
@ExperimentalCoroutinesApi
internal class SourceOfTruthWithBarrier<Key, Input, Output, Error>(
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

    fun reader(key: Key, lock: CompletableDeferred<Unit>): Flow<StoreResponse<Output?, Either<Error, Throwable>>> {
        return flow {
            val barrier = barriers.acquire(key)
            val readerVersion: Long = versionCounter.incrementAndGet()
            try {
                lock.await()
                emitAll(
                    barrier.asFlow()
                        .flatMapLatest {
                            val messageArrivedAfterMe = readerVersion < it.version
                            val writeError = if (messageArrivedAfterMe && it is BarrierMsg.Open) {
                                it.writeError
                            } else {
                                null
                            }
                            val readFlow: Flow<StoreResponse<Output?, Either<Error, Throwable>>> = when (it) {
                                is BarrierMsg.Open ->
                                    delegate.reader(key).mapIndexed { index, output ->
                                        if (index == 0 && messageArrivedAfterMe) {
                                            val firstMsgOrigin = if (writeError == null) {
                                                // restarted barrier without an error means write succeeded
                                                ResponseOrigin.Fetcher
                                            } else {
                                                // when a write fails, we still get a new reader because
                                                // we've disabled the previous reader before starting the
                                                // write operation. But since write has failed, we should
                                                // use the SourceOfTruth as the origin
                                                ResponseOrigin.SourceOfTruth
                                            }
                                            StoreResponse.Data(
                                                origin = firstMsgOrigin,
                                                value = output
                                            )
                                        } else {
                                            StoreResponse.Data(
                                                origin = ResponseOrigin.SourceOfTruth,
                                                value = output
                                            ) as StoreResponse<Output?, Either<Error, Throwable>> // necessary cast for catch block
                                        }
                                    }.catch { throwable ->
                                        this.emit(
                                            StoreResponse.Error(
                                                error = Either.Right(
                                                    SourceOfTruth.ReadException(
                                                        key = key,
                                                        cause = throwable
                                                    )
                                                ),
                                                origin = ResponseOrigin.SourceOfTruth
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
                                            StoreResponse.Error(
                                                origin = ResponseOrigin.SourceOfTruth,
                                                error = Either.Right(writeError)
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

    suspend fun write(key: Key, value: Input) {
        val barrier = barriers.acquire(key)
        try {
            barrier.send(BarrierMsg.Blocked(versionCounter.incrementAndGet()))
            val writeError = try {
                delegate.write(key, value)
                null
            } catch (throwable: Throwable) {
                if (throwable !is CancellationException) {
                    throwable
                } else {
                    null
                }
            }

            barrier.send(
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
