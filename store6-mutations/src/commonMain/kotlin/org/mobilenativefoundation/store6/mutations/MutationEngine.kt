@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.Overlay
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle

/**
 * Identifies a mutation that remains pending.
 *
 * PROVISIONAL pending Issue 021: the mutation API may change before that issue is complete.
 */
@ExperimentalStoreApi
public class PendingIntent internal constructor(
    /** The per-engine identifier assigned to the mutation. */
    @ExperimentalStoreApi
    public val mutationId: String,

    /** The identifier of the registered projection. */
    @ExperimentalStoreApi
    public val mutatorId: String,
)

/**
 * Describes a pending mutation whose projection threw.
 *
 * PROVISIONAL pending Issue 021: the mutation API may change before that issue is complete.
 */
@ExperimentalStoreApi
public class PoisonedIntent internal constructor(
    /** The per-engine identifier assigned to the mutation. */
    @ExperimentalStoreApi
    public val mutationId: String,

    /** The identifier of the registered projection that threw. */
    @ExperimentalStoreApi
    public val mutatorId: String,

    /** The exact failure thrown by the projection. */
    @ExperimentalStoreApi
    public val failure: Throwable,
)

internal class MutationEngine<K : StoreKey, V : Any>(
    private val registry: MutatorRegistry<K, V>,
    private val server: MutationServer<K, V>,
    private val journal: MutationJournal<V> = InMemoryMutationJournal(),
) {
    private val mutations = Mutex()
    private var nextMutationSequence = 0L
    private val signalSink = MutableSharedFlow<StoreKey>(replay = 1)
    private val poisonSink =
        MutableSharedFlow<PoisonedIntent>(
            replay = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private lateinit var handle: StoreWriteHandle<K, V>

    internal val changes: SharedFlow<StoreKey> = signalSink.asSharedFlow()
    internal val poisoned: SharedFlow<PoisonedIntent> = poisonSink.asSharedFlow()

    /**
     * Projects confirmed residence through the current pending intents.
     *
     * Store stamps a changed projection with `OVERLAY` origin, zero age, and no staleness.
     * `OVERLAY` is therefore the pending-write affordance; staleness is not. The shared [changes]
     * stream remains live and never completes or fails.
     */
    internal val overlay: Overlay<K, V> =
        object : Overlay<K, V> {
            override fun apply(
                key: K,
                base: V?,
            ): V? = projectAll(key, base)

            override val changes: Flow<StoreKey> = this@MutationEngine.changes
        }

    internal suspend fun <A : Any> mutate(
        key: K,
        ref: MutatorRef<K, V, A>,
        args: A,
    ): String {
        require(ref.ownership === registry.ownership) {
            "MutatorRef '${ref.id}' belongs to a different MutatorRegistry."
        }
        val mutationId =
            mutations.withLock {
                nextMutationSequence += 1
                val nextId = "mutation-$nextMutationSequence"
                journal.append(
                    key.identity(),
                    JournalEntry(
                        mutationId = nextId,
                        mutatorId = ref.id,
                        args = args,
                    ),
                )
            }
        signalChange(key)
        return mutationId
    }

    internal fun bind(handle: StoreWriteHandle<K, V>) {
        check(!this::handle.isInitialized) {
            "Mutation engine is already bound."
        }
        this.handle = handle
    }

    internal suspend fun drainOnce(
        key: K,
        confirmedBase: V?,
    ) {
        var projected = confirmedBase
        for (entry in journal.pendingSnapshot(key.identity())) {
            val result = project(projected, entry)
            if (!result.shouldDrain) return
            projected = result.value
            val value = projected ?: return
            val ack =
                try {
                    server.push(key, value)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    return
                }
            adopt(key, entry, ack)
            projected = ack.echo
        }
    }

    internal suspend fun pending(key: K): List<PendingIntent> =
        journal
            .pendingSnapshot(key.identity())
            .map { entry ->
                PendingIntent(
                    mutationId = entry.mutationId,
                    mutatorId = entry.mutatorId,
                )
            }

    internal fun projectAll(
        key: K,
        base: V?,
    ): V? =
        journal.pendingSnapshot(key.identity()).fold(base) { projected, entry ->
            project(projected, entry).value
        }

    private fun project(
        base: V?,
        entry: JournalEntry<V>,
    ): ProjectionResult<V> {
        val projection =
            registry.projections[entry.mutatorId]
                ?: return ProjectionResult(value = base, shouldDrain = false)
        return try {
            ProjectionResult(
                value = projection(base, entry.args),
                shouldDrain = true,
            )
        } catch (failure: Throwable) {
            poisonSink.tryEmit(
                PoisonedIntent(
                    mutationId = entry.mutationId,
                    mutatorId = entry.mutatorId,
                    failure = failure,
                ),
            )
            ProjectionResult(value = base, shouldDrain = false)
        }
    }

    private suspend fun adopt(
        key: K,
        entry: JournalEntry<V>,
        ack: MutationAck<V>,
    ) {
        handle.apply(key, ack.echo)
        handle.confirmFresh(key, ack.etag)
        journal.retire(key.identity(), entry.mutationId)
        signalChange(key)
    }

    private suspend fun signalChange(key: K) {
        // Once a journal transition completes, caller cancellation cannot discard its accepted
        // key-change handoff and strand a different key behind SharedFlow replay backpressure.
        withContext(NonCancellable) {
            signalSink.emit(key)
        }
    }
}

private class ProjectionResult<V : Any>(
    val value: V?,
    val shouldDrain: Boolean,
)
