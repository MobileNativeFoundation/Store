package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.mobilenativefoundation.store6.core.StoreResult

/**
 * Decouples Store result production from collection with a kind-bounded pending queue.
 *
 * The queue holds at most one pending element per [StoreResult] kind (≤ 4). The latest occurrence
 * wins for each kind, and delivery order is the relative order of those latest occurrences.
 * When pending results drain before the next same-kind emission, every emission is delivered. A
 * blocked collector instead receives at least the latest pending result per kind. This realizes
 * an O(1)-per-collector bound that covers lifecycle signals as well as data.
 *
 * A pathological fetch-error storm cannot grow a collector's buffer because the queue is
 * kind-bounded. This operator bounds delivery buffering only and adds or changes no engine retry
 * or backoff behavior. [StoreResult.Revalidated] is never conflated away in favor of another kind:
 * only a newer Revalidated supersedes an older queued one for a blocked collector, so the kind is
 * never lost.
 */
internal fun <V> Flow<StoreResult<V>>.conflateLatestData(): Flow<StoreResult<V>> = flow {
    var terminalFailure: Throwable? = null

    coroutineScope {
        val pending = ArrayDeque<StoreResult<V>>()
        val mutex = Mutex()
        val wakeVersion = MutableStateFlow(0L)
        var upstreamComplete = false
        var upstreamFailure: Throwable? = null

        val upstream = launch {
            try {
                this@conflateLatestData.collect { result ->
                    mutex.withLock {
                        pending.removeAll { queued -> sameKind(queued, result) }
                        pending.addLast(result)
                        wakeVersion.value += 1
                    }
                    yield()
                }
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                mutex.withLock { upstreamFailure = failure }
            } finally {
                mutex.withLock {
                    upstreamComplete = true
                    wakeVersion.value += 1
                }
            }
        }

        try {
            while (true) {
                var next: StoreResult<V>? = null
                var complete = false
                var failure: Throwable? = null
                var observedWakeVersion = 0L

                mutex.withLock {
                    if (pending.isNotEmpty()) {
                        next = pending.removeFirst()
                    } else if (upstreamComplete) {
                        complete = true
                        failure = upstreamFailure
                    } else {
                        observedWakeVersion = wakeVersion.value
                    }
                }

                when {
                    next != null -> emit(next!!)
                    complete -> {
                        terminalFailure = failure
                        break
                    }
                    else -> wakeVersion.first { it > observedWakeVersion }
                }
            }
        } finally {
            upstream.cancel()
        }
    }

    terminalFailure?.let { throw it }
}

/** Two results share a kind when a newer one supersedes the older for a blocked collector. */
private fun sameKind(a: StoreResult<*>, b: StoreResult<*>): Boolean =
    when (a) {
        is StoreResult.Data<*> -> b is StoreResult.Data<*>
        is StoreResult.Loading -> b is StoreResult.Loading
        is StoreResult.Revalidated -> b is StoreResult.Revalidated
        is StoreResult.Error -> b is StoreResult.Error
    }
