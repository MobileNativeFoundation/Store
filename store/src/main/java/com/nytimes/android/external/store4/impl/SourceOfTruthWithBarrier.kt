package com.nytimes.android.external.store4.impl

import com.nytimes.android.external.store4.ResponseOrigin
import com.nytimes.android.external.store4.impl.operators.mapIndexed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.*
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
            create = { key ->
                ConflatedBroadcastChannel(BarrierMsg.Open.INITIAL)
            }
    )
    /**
     * Each message gets dispatched with a version. This ensures we won't accidentally turn on the
     * reader flow for a new reader that happens to have arrived while a write is in progress since
     * that write should be considered as a disk read for that flow, not fetcher.
     */
    private val versionCounter = AtomicLong(0)


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
                                    DataWithOrigin<Output>(
                                            origin = ResponseOrigin.Fetcher,
                                            value = output
                                    )
                                } else {
                                    DataWithOrigin<Output>(
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
