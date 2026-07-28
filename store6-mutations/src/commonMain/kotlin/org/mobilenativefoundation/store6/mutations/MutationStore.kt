@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreBuilder
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.core.seam.KeyEvents
import org.mobilenativefoundation.store6.core.seam.runtime

/**
 * Pushes projected mutation state to an application-owned backend.
 *
 * PROVISIONAL pending Issue 021: Phase 2 sync is push-only and that issue reviews the final wire
 * shape. This interface deliberately has no pull member.
 */
@ExperimentalStoreApi
public fun interface MutationServer<K : StoreKey, V : Any> {
    /** Pushes [value] for [key] and returns the backend's confirmed echo. */
    @ExperimentalStoreApi
    public suspend fun push(
        key: K,
        value: V,
    ): MutationAck<V>
}

/**
 * Carries the confirmed backend echo adopted by Store.
 *
 * PROVISIONAL pending Issue 021: acknowledgement metadata and construction may change before that
 * issue is complete.
 */
@ExperimentalStoreApi
public class MutationAck<V : Any> public constructor(
    /** The backend-confirmed value written into Store's source of truth. */
    @ExperimentalStoreApi
    public val echo: V,

    /** Optional backend entity tag recorded by Store freshness bookkeeping. */
    @ExperimentalStoreApi
    public val etag: String?,
)

/**
 * Narrows a [Store] to journalled mutation writes while preserving Store reads and maintenance.
 *
 * PROVISIONAL pending Issue 021: this facade deliberately withholds the raw engine write handle.
 * Calling `runtime()` on it returns `null`; [keyEvents] is re-published so advisory access survives
 * that narrowing. [close] marks the facade closed before closing its delegate, and mutation
 * operations then fail with Store's exact closed-store contract.
 */
@ExperimentalStoreApi
public class MutationStore<K : StoreKey, V : Any> internal constructor(
    private val delegate: Store<K, V>,
    private val engine: MutationEngine<K, V>,

    /** The exact advisory event flow captured from the delegate runtime during construction. */
    @ExperimentalStoreApi
    public val keyEvents: Flow<KeyEvents>,
) : Store<K, V> by delegate {
    private val closed = MutableStateFlow(false)
    private val drainPass = Mutex()

    /** Appends one typed intent to the journal and signals optimistic reprojection. */
    @ExperimentalStoreApi
    public suspend fun <A : Any> mutate(
        key: K,
        ref: MutatorRef<K, V, A>,
        args: A,
    ): String {
        checkOpen()
        return engine.mutate(key, ref, args)
    }

    /**
     * Pushes the current pending FIFO prefix once, with no retry, backoff, or parking.
     *
     * Reads the unprojected confirmed base with [Freshness.LocalOnly], so this pass never fetches.
     * A missing base becomes `null` for create projection. Push failure stops normally with the
     * current intent pending; adoption failure propagates. This walking-skeleton facade serializes
     * drain passes store-wide; Issue 023 owns per-key scheduling and parallelism.
     */
    @ExperimentalStoreApi
    public suspend fun drainOnce(key: K) {
        drainPass.withLock {
            checkOpen()
            val confirmedBase =
                try {
                    delegate.get(key, Freshness.LocalOnly)
                } catch (failure: StoreException) {
                    if (failure.error is StoreError.Missing) {
                        null
                    } else {
                        throw failure
                    }
                }
            engine.drainOnce(key, confirmedBase)
        }
    }

    /** Returns the current pending intents for [key] in FIFO order. */
    @ExperimentalStoreApi
    public suspend fun pending(key: K): List<PendingIntent> {
        checkOpen()
        return engine.pending(key)
    }

    /** The replay-buffered projection-failure containment stream owned by the mutation engine. */
    @ExperimentalStoreApi
    public val poisoned: SharedFlow<PoisonedIntent>
        get() = engine.poisoned

    override fun close() {
        closed.value = true
        delegate.close()
    }

    private fun checkOpen() {
        if (closed.value) {
            throw IllegalStateException("Store is closed.")
        }
    }
}

/**
 * Builds a Store whose last-installed overlay is the mutation engine and returns its narrowed
 * facade.
 *
 * PROVISIONAL pending Issue 021: the builder-wrapping entry point may change so Issue 024 can make
 * transactional persistence visible to the factory. The delegate runtime is captured exactly
 * once, its write handle is bound privately, and neither runtime nor handle is exposed.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> mutationStore(
    registry: MutatorRegistry<K, V>,
    server: MutationServer<K, V>,
    configure: StoreBuilder<K, V>.() -> Unit,
): MutationStore<K, V> {
    val engine = MutationEngine(registry, server)
    val delegate =
        store<K, V> {
            configure()
            overlay(engine.overlay)
        }
    val runtime = checkNotNull(delegate.runtime())
    engine.bind(runtime.writeHandle)
    return MutationStore(
        delegate = delegate,
        engine = engine,
        keyEvents = runtime.keyEvents,
    )
}
