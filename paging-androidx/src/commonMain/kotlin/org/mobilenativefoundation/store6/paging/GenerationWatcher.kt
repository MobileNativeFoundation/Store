package org.mobilenativefoundation.store6.paging

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreResult

/** Owns the live Store collectors associated with one paging-source generation. */
internal class GenerationWatcher<K : StoreKey, V : Any>(
    private val store: Store<K, V>,
    private val invalidateGeneration: () -> Unit,
) {
    internal sealed interface BaselineResult<out T : Any> {
        class Frame<T : Any> internal constructor(
            val result: StoreResult<T>,
            internal val entry: Entry<T>,
        ) : BaselineResult<T>

        data class Failure(val throwable: Throwable) : BaselineResult<Nothing>

        data object Stopped : BaselineResult<Nothing>
    }

    private data class KeyId(
        val namespace: String,
        val canonicalId: String,
    )

    internal sealed interface Resolution {
        data object ARMED : Resolution

        data object DISCARDED : Resolution

        data object STOPPED : Resolution

        data class Failed(val throwable: Throwable) : Resolution
    }

    internal class Entry<V : Any> {
        val baseline = CompletableDeferred<BaselineResult<V>>()
        val armed = CompletableDeferred<Unit>()
        val resolution = CompletableDeferred<Resolution>()
        var job: Job? = null
    }

    private val generationJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + generationJob)
    private val mutex = Mutex()
    private val entries = mutableMapOf<KeyId, Entry<V>>()
    private var invalidationRequested = false

    suspend fun baseline(
        key: K,
        freshness: Freshness,
    ): BaselineResult<V> {
        val id = KeyId(key.namespace.value, key.canonicalId())
        while (generationJob.isActive) {
            val (entry, ownsEntry) =
                mutex.withLock {
                    val existing = entries[id]
                    if (existing != null) {
                        existing to false
                    } else {
                        Entry<V>().also { entries[id] = it } to true
                    }
                }

            if (!ownsEntry) {
                when (val resolution = entry.resolution.await()) {
                    Resolution.ARMED -> {
                        requestInvalidation()
                        return BaselineResult.Stopped
                    }
                    Resolution.DISCARDED -> continue
                    Resolution.STOPPED -> return BaselineResult.Stopped
                    is Resolution.Failed -> return BaselineResult.Failure(resolution.throwable)
                }
            }

            entry.job =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    var baselineObserved = false
                    try {
                        store.stream(key, freshness).collect { result ->
                            if (!baselineObserved) {
                                if (result is StoreResult.Loading) return@collect
                                baselineObserved = true
                                entry.baseline.complete(BaselineResult.Frame(result, entry))
                                entry.armed.await()
                            } else {
                                when (result) {
                                    is StoreResult.Data,
                                    is StoreResult.Loading,
                                    -> requestInvalidation()
                                    is StoreResult.Error,
                                    is StoreResult.Revalidated,
                                    -> Unit
                                }
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        if (
                            !baselineObserved &&
                            entry.baseline.complete(BaselineResult.Failure(failure))
                        ) {
                            entry.resolution.complete(Resolution.Failed(failure))
                        } else {
                            requestInvalidation()
                        }
                    } finally {
                        if (!entry.baseline.isCompleted) {
                            entry.baseline.complete(BaselineResult.Stopped)
                        }
                        if (!entry.resolution.isCompleted) {
                            entry.resolution.complete(Resolution.STOPPED)
                        }
                    }
                }

            try {
                return entry.baseline.await()
            } catch (cancellation: CancellationException) {
                withContext(NonCancellable) { abandon(id, entry) }
                throw cancellation
            }
        }
        return BaselineResult.Stopped
    }

    suspend fun watch(
        key: K,
        baselineFrameObserved: BaselineResult.Frame<V>,
    ) {
        val entry = baselineFrameObserved.entry
        val id = KeyId(key.namespace.value, key.canonicalId())
        val arm =
            mutex.withLock {
                val ownsEntry = entries[id] === entry
                if (ownsEntry && generationJob.isActive && !invalidationRequested) {
                    entry.resolution.complete(Resolution.ARMED)
                    true
                } else {
                    if (ownsEntry) entries.remove(id)
                    if (!entry.resolution.isCompleted) {
                        entry.resolution.complete(Resolution.STOPPED)
                    }
                    false
                }
            }
        if (arm) {
            entry.armed.complete(Unit)
        } else {
            entry.job?.cancel()
        }
    }

    suspend fun discard(
        key: K,
        baselineFrameObserved: BaselineResult.Frame<V>,
    ) {
        val entry = baselineFrameObserved.entry
        val id = KeyId(key.namespace.value, key.canonicalId())
        mutex.withLock {
            if (entries[id] === entry) entries.remove(id)
            if (!entry.resolution.isCompleted) {
                entry.resolution.complete(Resolution.DISCARDED)
            }
        }
        entry.job?.cancel()
    }

    fun cancel() {
        generationJob.cancel()
    }

    private suspend fun requestInvalidation() {
        val shouldInvalidate =
            mutex.withLock {
                if (invalidationRequested || !generationJob.isActive) {
                    false
                } else {
                    invalidationRequested = true
                    true
                }
            }
        // Paging invalidation cancels this scope synchronously through its registered callback.
        // Invoke it only after releasing the mutex so callback cleanup cannot reenter the lock.
        if (shouldInvalidate) invalidateGeneration()
    }

    private suspend fun abandon(
        id: KeyId,
        entry: Entry<V>,
    ) {
        val cancelCollector =
            mutex.withLock {
                if (entries[id] === entry && !entry.resolution.isCompleted) {
                    entries.remove(id)
                    entry.resolution.complete(Resolution.DISCARDED)
                    true
                } else {
                    false
                }
            }
        if (cancelCollector) entry.job?.cancel()
    }
}
