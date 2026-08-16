package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ConflateLatestDataTest {

    @Test
    fun slowCollector_getsLatestDataAndEveryLifecycleSignalBeforeCompletion() = runTest {
        val firstDataSeen = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val upstreamCompleted = CompletableDeferred<Unit>()
        val received = mutableListOf<StoreResult<Int>>()

        val upstream = flow<StoreResult<Int>> {
            emit(data(1))
            firstDataSeen.await()
            emit(data(2))
            emit(data(3))
            emit(StoreResult.Loading())
            emit(StoreResult.Loading())
            emit(StoreResult.Revalidated(age = Duration.ZERO))
            emit(
                StoreResult.Error(
                    error = StoreError.Fetch(message = "fetch failed", cause = null),
                    servedStale = true,
                ),
            )
            upstreamCompleted.complete(Unit)
        }

        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            upstream.conflateLatestData().collect { result ->
                received += result
                if (result is StoreResult.Data && result.value == 1) {
                    firstDataSeen.complete(Unit)
                    releaseCollector.await()
                }
            }
        }

        firstDataSeen.await()
        upstreamCompleted.await()
        assertTrue(collector.isActive)

        releaseCollector.complete(Unit)
        collector.join()

        assertEquals(
            listOf("data:1", "data:3", "loading", "revalidated", "error"),
            received.map(::label),
        )
    }

    @Test
    fun blockedCollector_queueBoundedAcrossManyRevalidationCycles() = runTest {
        val firstDataSeen = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val upstreamCompleted = CompletableDeferred<Unit>()
        val received = mutableListOf<StoreResult<Int>>()

        val upstream = flow<StoreResult<Int>> {
            emit(data(0))
            firstDataSeen.await()
            repeat(1_000) { cycle ->
                emit(data(cycle + 1))
                emit(StoreResult.Loading())
                emit(
                    StoreResult.Error(
                        error = StoreError.Fetch(message = "e$cycle", cause = null),
                        servedStale = false,
                    ),
                )
                emit(StoreResult.Revalidated(age = Duration.ZERO))
            }
            emit(data(9_999))
            upstreamCompleted.complete(Unit)
        }

        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            upstream.conflateLatestData().collect { result ->
                received += result
                if (result is StoreResult.Data && result.value == 0) {
                    firstDataSeen.complete(Unit)
                    releaseCollector.await() // park: 4_001 further emissions coalesce in the queue
                }
            }
        }

        firstDataSeen.await()
        upstreamCompleted.await()
        releaseCollector.complete(Unit)
        collector.join()

        // First delivered frame + at most one queued element per kind (<= 4) = <= 5 total.
        assertTrue(
            received.size <= 5,
            "blocked collector saw ${received.size} results; bound is O(kinds)",
        )
        assertEquals(
            9_999,
            (received.last { it is StoreResult.Data } as StoreResult.Data<Int>).value,
        )
        assertTrue(received.any { it is StoreResult.Error })
        assertTrue(received.any { it is StoreResult.Revalidated })
        assertTrue(received.any { it is StoreResult.Loading })
    }

    @Test
    fun blockedCollector_removeAndAppendKeepsLatestPayloadsInRelativeOccurrenceOrder() = runTest {
        val firstDataSeen = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val upstreamCompleted = CompletableDeferred<Unit>()
        val received = mutableListOf<StoreResult<Int>>()

        val upstream = flow<StoreResult<Int>> {
            emit(data(0))
            firstDataSeen.await()
            emit(
                StoreResult.Error(
                    error = StoreError.Fetch(message = "old-error", cause = null),
                    servedStale = false,
                ),
            )
            emit(StoreResult.Revalidated(age = 1.seconds))
            emit(StoreResult.Loading())
            emit(
                StoreResult.Error(
                    error = StoreError.Fetch(message = "latest-error", cause = null),
                    servedStale = true,
                ),
            )
            emit(StoreResult.Revalidated(age = 2.seconds))
            emit(data(42))
            upstreamCompleted.complete(Unit)
        }

        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            upstream.conflateLatestData().collect { result ->
                received += result
                if (result is StoreResult.Data && result.value == 0) {
                    firstDataSeen.complete(Unit)
                    releaseCollector.await()
                }
            }
        }

        firstDataSeen.await()
        upstreamCompleted.await()
        releaseCollector.complete(Unit)
        collector.join()

        assertEquals(
            listOf("data:0", "loading", "error", "revalidated", "data:42"),
            received.map(::label),
        )
        val latestError = received.filterIsInstance<StoreResult.Error>().single()
        assertEquals("latest-error", (latestError.error as StoreError.Fetch).message)
        assertTrue(latestError.servedStale)
        assertEquals(
            2.seconds,
            received.filterIsInstance<StoreResult.Revalidated>().single().age,
        )
    }

    @Test
    fun immediateCollector_getsEverySynchronousDataEmission() = runTest {
        val received = mutableListOf<StoreResult<Int>>()

        val upstream = flow<StoreResult<Int>> {
            emit(data(1))
            emit(data(2))
            emit(data(3))
        }

        upstream.conflateLatestData().collect { result ->
            received += result
        }

        assertEquals(
            listOf("data:1", "data:2", "data:3"),
            received.map(::label),
        )
    }

    @Test
    fun eachCollector_hasIndependentConflationState() = runTest {
        var subscriptions = 0
        val upstream = flow<StoreResult<Int>> {
            subscriptions += 1
            emit(data(subscriptions))
        }
        val first = mutableListOf<StoreResult<Int>>()
        val second = mutableListOf<StoreResult<Int>>()
        val conflated = upstream.conflateLatestData()

        conflated.collect(first::add)
        conflated.collect(second::add)

        assertEquals(listOf("data:1"), first.map(::label))
        assertEquals(listOf("data:2"), second.map(::label))
    }

    @Test
    fun cancellingCollector_cancelsUpstream() = runTest {
        val upstreamStarted = CompletableDeferred<Unit>()
        val upstreamCancelled = CompletableDeferred<Unit>()
        val upstream = flow<StoreResult<Int>> {
            upstreamStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                upstreamCancelled.complete(Unit)
            }
        }

        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            upstream.conflateLatestData().collect()
        }
        upstreamStarted.await()

        collector.cancelAndJoin()

        upstreamCancelled.await()
    }

    @Test
    fun explicitUpstreamCancellation_drainsQueuedValuesThenPropagatesExactFailure() = runTest {
        val firstDataSeen = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val upstreamFinished = CompletableDeferred<Unit>()
        val explicitCancellation = CancellationException("explicit upstream cancellation")
        val received = mutableListOf<StoreResult<Int>>()

        val collector = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                flow<StoreResult<Int>> {
                    try {
                        emit(data(1))
                        firstDataSeen.await()
                        emit(data(2))
                        emit(StoreResult.Loading())
                        throw explicitCancellation
                    } finally {
                        upstreamFinished.complete(Unit)
                    }
                }.conflateLatestData().collect { result ->
                    received += result
                    if (result is StoreResult.Data && result.value == 1) {
                        firstDataSeen.complete(Unit)
                        releaseCollector.await()
                    }
                }
            }.exceptionOrNull()
        }

        firstDataSeen.await()
        upstreamFinished.await()
        releaseCollector.complete(Unit)

        assertSame(explicitCancellation, collector.await())
        assertEquals(listOf("data:1", "data:2", "loading"), received.map(::label))
    }

    @Test
    fun upstreamFailure_drainsQueuedValuesThenPropagatesExactFailure() = runTest {
        val firstDataSeen = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val upstreamFinished = CompletableDeferred<Unit>()
        val upstreamFailure = IllegalStateException("upstream failed")
        val received = mutableListOf<StoreResult<Int>>()

        val collector = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                flow<StoreResult<Int>> {
                    try {
                        emit(data(1))
                        firstDataSeen.await()
                        emit(data(2))
                        emit(StoreResult.Revalidated(age = Duration.ZERO))
                        throw upstreamFailure
                    } finally {
                        upstreamFinished.complete(Unit)
                    }
                }.conflateLatestData().collect { result ->
                    received += result
                    if (result is StoreResult.Data && result.value == 1) {
                        firstDataSeen.complete(Unit)
                        releaseCollector.await()
                    }
                }
            }.exceptionOrNull()
        }

        firstDataSeen.await()
        upstreamFinished.await()
        releaseCollector.complete(Unit)

        assertSame(upstreamFailure, collector.await())
        assertEquals(listOf("data:1", "data:2", "revalidated"), received.map(::label))
    }

    @Test
    fun downstreamCollectorFailure_cancelsUpstream() = runTest {
        val upstreamStarted = CompletableDeferred<Unit>()
        val upstreamCancelled = CompletableDeferred<Unit>()
        val collectorFailure = IllegalStateException("collector failed")
        val upstream = flow<StoreResult<Int>> {
            upstreamStarted.complete(Unit)
            try {
                emit(data(1))
                awaitCancellation()
            } finally {
                upstreamCancelled.complete(Unit)
            }
        }

        val observedFailure = runCatching {
            upstream.conflateLatestData().collect {
                throw collectorFailure
            }
        }.exceptionOrNull()

        upstreamStarted.await()
        upstreamCancelled.await()
        assertTrue(
            observedFailure === collectorFailure || observedFailure?.cause === collectorFailure,
            "collector failure was not propagated",
        )
    }

    private fun data(value: Int): StoreResult.Data<Int> =
        StoreResult.Data(
            value = value,
            origin = Origin.MEMORY,
            age = Duration.ZERO,
            isStale = false,
            refreshing = false,
        )

    private fun label(result: StoreResult<Int>): String =
        when (result) {
            is StoreResult.Data -> "data:${result.value}"
            is StoreResult.Loading -> "loading"
            is StoreResult.Revalidated -> "revalidated"
            is StoreResult.Error -> "error"
        }
}
