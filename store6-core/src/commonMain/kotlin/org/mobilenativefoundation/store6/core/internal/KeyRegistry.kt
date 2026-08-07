package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.StoreKey

/**
 * Owns at most one [KeyEngine] per canonical [KeyId], refcounted, with quiescent-only LRU idling.
 *
 * Structure:
 * - [active] holds engines with refCount >= 1 or a not-yet-idled zero-ref engine awaiting its
 *   fetch reference release; [idle] holds only quiescent zero-ref engines in LRU (insertion)
 *   order. The maps are disjoint. Eviction reads only [idle], and acquisition removes from
 *   [idle] under the same lock, so evicting a held engine is unrepresentable.
 * - References: every withEngine bracket (stream collection lifetime, get/maintenance call
 *   duration), every bulk-maintenance sweep entry, and every fetch job (via
 *   [EngineResidencyHooks]) holds one reference.
 * - Eviction destroys only derived state; durable truth lives in the source of truth and
 *   bookkeeper, so a recreated engine is semantically identical: hydration restamps freshness
 *   from the bookkeeper's persisted status. Total resident engines <= active references + maxIdle.
 * - Bulk sweeps retain each snapshotted engine for the action's duration, preserving the
 *   double-sweep-under-fence semantics: watermarks cover engines missed by a snapshot; an engine
 *   inserted between bulk-clear sweeps is included by purge or remains fenced until maintenance
 *   releases.
 * - Creation still runs [verifyStableCanonicalId] once per residency.
 */
internal class KeyRegistry<K : StoreKey, V : Any>(
    private val maxIdle: Int,
    private val createEngine: (K, KeyId, EngineResidencyHooks) -> KeyEngine<K, V>,
) {
    private class Handle<K : StoreKey, V : Any>(
        val engine: KeyEngine<K, V>,
    ) {
        var refCount: Int = 0
    }

    private val lock = Mutex()
    private val active = HashMap<KeyId, Handle<K, V>>()
    private val idle = LinkedHashMap<KeyId, Handle<K, V>>()
    private var closed = false
    private var created = 0L
    private var destroyed = 0L

    internal suspend fun <R> withEngine(
        key: K,
        block: suspend (KeyEngine<K, V>) -> R,
    ): R {
        val id = KeyId.from(key)
        val handle =
            lock.withLock {
                if (closed) throw storeClosedException()
                val resolved =
                    active[id]
                        ?: idle.remove(id)?.also { revived -> active[id] = revived }
                        ?: Handle(newEngine(key, id)).also { fresh ->
                            active[id] = fresh
                            created += 1
                        }
                resolved.refCount += 1
                resolved
            }
        try {
            return block(handle.engine)
        } finally {
            withContext(NonCancellable) { release(id) }
        }
    }

    internal suspend fun forEachResident(
        namespace: String?,
        action: suspend (KeyEngine<K, V>) -> Unit,
    ) {
        snapshotAndForEachResident(namespace, action)
    }

    /** Returns the exact retained snapshot acted on when a caller must notify it after a fence. */
    internal suspend fun snapshotAndForEachResident(
        namespace: String?,
        action: suspend (KeyEngine<K, V>) -> Unit,
    ): List<KeyEngine<K, V>> {
        val retained =
            lock.withLock {
                if (closed) return emptyList()
                val ids =
                    (active.keys + idle.keys).filter { id ->
                        namespace == null || id.namespace == namespace
                    }
                ids.map { id ->
                    val handle = active[id] ?: checkNotNull(idle.remove(id)).also { active[id] = it }
                    handle.refCount += 1
                    id to handle
                }
            }
        try {
            retained.forEach { (_, handle) -> action(handle.engine) }
        } finally {
            withContext(NonCancellable) { retained.forEach { (id, _) -> release(id) } }
        }
        return retained.map { (_, handle) -> handle.engine }
    }

    /** Releases one reference; at zero references a quiescent engine idles and overflow evicts. */
    private suspend fun release(id: KeyId) {
        val overflow =
            lock.withLock {
                val handle = active[id] ?: return@withLock emptyList()
                handle.refCount -= 1
                if (handle.refCount > 0) return@withLock emptyList()
                // A non-quiescent zero-ref engine holds an in-flight fetch whose own reference
                // release re-runs this check, so it deliberately stays active until then.
                if (!handle.engine.isQuiescentForIdle()) return@withLock emptyList()
                active.remove(id)
                if (maxIdle == 0) {
                    destroyed += 1
                    return@withLock listOf(handle.engine)
                }
                idle[id] = handle
                val evicted = ArrayList<KeyEngine<K, V>>()
                while (idle.size > maxIdle) {
                    val eldestId = idle.keys.first()
                    val eldest = idle.remove(eldestId) ?: break
                    destroyed += 1
                    evicted += eldest.engine
                }
                evicted
            }
        overflow.forEach { engine -> engine.destroy() }
    }

    /**
     * Drops residency state at close. Every critical section is non-suspending, so the lock is
     * effectively always free here; the caller also re-invokes this from the store job's
     * completion handler. Present behavior: if a concurrent non-suspending section holds the lock
     * at both attempts, the maps are released when the store becomes unreachable — entries are
     * bounded by resident engines at close and every engine scope is already cancelled.
     */
    internal fun clearOnClose() {
        if (!lock.tryLock()) return
        try {
            closed = true
            active.clear()
            idle.clear()
        } finally {
            lock.unlock()
        }
    }

    internal suspend fun residentCountForTest(): Int = lock.withLock { active.size + idle.size }

    internal suspend fun idleCountForTest(): Int = lock.withLock { idle.size }

    internal suspend fun createdCountForTest(): Long = lock.withLock { created }

    internal suspend fun destroyedCountForTest(): Long = lock.withLock { destroyed }

    private fun newEngine(
        key: K,
        id: KeyId,
    ): KeyEngine<K, V> {
        verifyStableCanonicalId(key, id)
        return createEngine(key, id, HandleHooks(id))
    }

    private inner class HandleHooks(private val id: KeyId) : EngineResidencyHooks {
        override suspend fun retainFetchRef() {
            lock.withLock {
                val handle = active[id] ?: idle.remove(id)?.also { active[id] = it } ?: return
                handle.refCount += 1
            }
        }

        override suspend fun releaseFetchRef() {
            withContext(NonCancellable) { release(id) }
        }
    }

    private fun verifyStableCanonicalId(
        key: K,
        id: KeyId,
    ) {
        val second = key.canonicalId()
        check(id.canonicalId == second) {
            "StoreKey ${key::class.simpleName ?: "<anonymous>"} in namespace " +
                "'${id.namespace}' returned two different canonical ids for the same " +
                "instance ('${id.canonicalId}', then '$second'). canonicalId() is the key's " +
                "durable identity and must be a pure, stable function of the key's immutable " +
                "fields. Fix the key type so repeated calls always return the same string."
        }
    }
}
