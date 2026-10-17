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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store.store5.Converter
import org.mobilenativefoundation.store.store5.SourceOfTruth
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin

/**
 * Wraps a [SourceOfTruth] and blocks reads while a write is in progress.
 *
 * Used in the [RealStore] implementation to avoid
 * dispatching values to downstream while a write is in progress.
 */
@Suppress("UNCHECKED_CAST")
internal class SourceOfTruthWithBarrier<Key : Any, Network : Any, Output : Any, Local : Any>(
    private val delegate: SourceOfTruth<Key, Local, Output>,
    private val converter: Converter<Network, Local, Output>? = null,
) {
    /**
     * Each key has a barrier so that we can block reads while writing.
     */
    private val barriers =
        RefCountedResource<Key, MutableStateFlow<BarrierMsg>>(
            create = {
                MutableStateFlow(BarrierMsg.Open.INITIAL)
            },
        )

    /**
     * Each message gets dispatched with a version. This ensures we won't accidentally turn on the
     * reader flow for a new reader that happens to have arrived while a write is in progress since
     * that write should be considered as a disk read for that flow, not fetcher.
     */
    private val versionCounter = atomic(0L)

    fun reader(
        key: Key,
        lock: CompletableDeferred<Unit>,
    ): Flow<StoreReadResponse<Output?>> {
        return flow {
            val barrier = barriers.acquire(key)
            val readerVersion: Long = versionCounter.incrementAndGet()
            try {
                lock.await()
                // Keep one collection of a live reader across writes. flatMapLatest used to cancel
                // it on every barrier transition, which restarted Room and channelFlow readers
                // (#572). A one-shot reader completes after its value, so it is collected again
                // only after that. The writer can emit the new row before this coroutine observes
                // the barrier reopen; that value is the post-write read.
                coroutineScope {
                    val signals = Channel<ReaderSignal<Output>>(Channel.UNLIMITED)
                    val barrierJob =
                        launch {
                            barrier.collect { message ->
                                signals.send(ReaderSignal.Barrier(message))
                            }
                        }
                    var readerJob: Job? = null

                    fun startReader() {
                        readerJob =
                            launch {
                                try {
                                    delegate.reader(key).collect { value ->
                                        signals.send(ReaderSignal.Value(value, barrier.value))
                                    }
                                    signals.send(ReaderSignal.Completed)
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (throwable: Throwable) {
                                    signals.send(ReaderSignal.Failed(throwable.cause ?: throwable))
                                }
                            }
                    }

                    var pendingValue: Output? = null
                    var hasPending = false
                    var announcedVersion = Long.MIN_VALUE
                    var emittedVersion = Long.MIN_VALUE
                    try {
                        for (signal in signals) {
                            when (signal) {
                                is ReaderSignal.Barrier -> {
                                    val message = signal.message
                                    if (message is BarrierMsg.Open) {
                                        val afterMe = readerVersion < message.version
                                        if (afterMe && announcedVersion < message.version) {
                                            message.writeError?.let { writeError ->
                                                emit(
                                                    StoreReadResponse.Error.Exception(
                                                        origin = StoreReadResponseOrigin.SourceOfTruth,
                                                        error = writeError,
                                                    ),
                                                )
                                            }
                                            announcedVersion = message.version
                                            if (hasPending && emittedVersion < message.version) {
                                                emit(
                                                    StoreReadResponse.Data(
                                                        origin = postWriteOrigin(message),
                                                        value = pendingValue,
                                                    ),
                                                )
                                                emittedVersion = message.version
                                                hasPending = false
                                                pendingValue = null
                                            }
                                        }
                                        if (readerJob?.isActive != true) {
                                            startReader()
                                        }
                                    }
                                }

                                is ReaderSignal.Value -> {
                                    val snapshot = signal.snapshot
                                    if (snapshot !is BarrierMsg.Open) {
                                        pendingValue = signal.value
                                        hasPending = true
                                    } else if (
                                        readerVersion < snapshot.version &&
                                        emittedVersion < snapshot.version &&
                                        announcedVersion < snapshot.version
                                    ) {
                                        pendingValue = signal.value
                                        hasPending = true
                                    } else if (
                                        readerVersion < snapshot.version &&
                                        emittedVersion < snapshot.version
                                    ) {
                                        emit(
                                            StoreReadResponse.Data(
                                                origin = postWriteOrigin(snapshot),
                                                value = signal.value,
                                            ),
                                        )
                                        emittedVersion = snapshot.version
                                        hasPending = false
                                        pendingValue = null
                                    } else {
                                        emit(
                                            StoreReadResponse.Data(
                                                origin = StoreReadResponseOrigin.SourceOfTruth,
                                                value = signal.value,
                                            ),
                                        )
                                    }
                                }

                                is ReaderSignal.Failed -> {
                                    emit(
                                        StoreReadResponse.Error.Exception(
                                            origin = StoreReadResponseOrigin.SourceOfTruth,
                                            error =
                                                SourceOfTruth.ReadException(
                                                    key = key,
                                                    cause = signal.cause,
                                                ),
                                        ),
                                    )
                                }

                                ReaderSignal.Completed -> Unit
                            }
                        }
                    } finally {
                        barrierJob.cancel()
                        readerJob?.cancel()
                    }
                }
            } finally {
                // we are using a finally here instead of onCompletion as there might be a
                // possibility where flow gets cancelled right before collection starts.
                barriers.release(key, barrier)
            }
        }
    }

    private fun postWriteOrigin(message: BarrierMsg.Open): StoreReadResponseOrigin =
        if (message.writeError == null) {
            StoreReadResponseOrigin.Fetcher()
        } else {
            StoreReadResponseOrigin.SourceOfTruth
        }

    /**
     * Writes a value to the underlying [SourceOfTruth] and returns any error that occurred.
     *
     * @return The [SourceOfTruth.WriteException] if the write failed, or null if successful.
     *         Callers like [RealStore.write] can check this to determine if the write succeeded.
     *         The barrier mechanism also notifies readers of the error via [BarrierMsg.Open.writeError].
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun write(
        key: Key,
        value: Local,
    ): SourceOfTruth.WriteException? {
        val barrier = barriers.acquire(key)
        var writeException: SourceOfTruth.WriteException? = null
        try {
            barrier.value = BarrierMsg.Blocked(versionCounter.incrementAndGet())
            try {
                delegate.write(key, value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // Preserve an adapter's existing wrapper and its original cause.
                writeException =
                    throwable as? SourceOfTruth.WriteException
                        ?: SourceOfTruth.WriteException(
                            key = key,
                            value = value,
                            cause = throwable,
                        )
            }
            return writeException
        } finally {
            // Reopen without suspending even when the writer's job was cancelled.
            barrier.value =
                BarrierMsg.Open(
                    version = versionCounter.incrementAndGet(),
                    writeError = writeException,
                )
            withContext(NonCancellable) {
                barriers.release(key, barrier)
            }
        }
    }

    suspend fun delete(key: Key) {
        delegate.delete(key)
    }

    suspend fun deleteAll() {
        delegate.deleteAll()
    }

    private sealed interface ReaderSignal<out Output> {
        class Barrier(
            val message: BarrierMsg,
        ) : ReaderSignal<Nothing>

        class Value<Output>(
            val value: Output?,
            val snapshot: BarrierMsg,
        ) : ReaderSignal<Output>

        class Failed(
            val cause: Throwable,
        ) : ReaderSignal<Nothing>

        data object Completed : ReaderSignal<Nothing>
    }

    private sealed class BarrierMsg(
        val version: Long,
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
