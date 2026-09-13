@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.WallClock
import org.mobilenativefoundation.store6.mutations.MutationCheckpointFailed
import org.mobilenativefoundation.store6.mutations.MutationEnqueued
import org.mobilenativefoundation.store6.mutations.MutationStore
import org.mobilenativefoundation.store6.mutations.drain.internal.DrainRegistration
import org.mobilenativefoundation.store6.mutations.drain.internal.DrainRegistry
import org.mobilenativefoundation.store6.mutations.drain.internal.deriveFollowUp
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * Coordinates registered mutation stores with a constraint-gated [DrainScheduler]. Each pass
 * drains one store, derives remaining work from journal inspection, and replaces or cancels the
 * tracked activation.
 */
@ExperimentalStoreApi
public class MutationDrainCoordinator internal constructor(
    private val scheduler: DrainScheduler,
    private val wallClock: WallClock,
) {
    private val registry = DrainRegistry()
    private val eventSink =
        MutableSharedFlow<DrainSchedulerEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Registers [store] under [name] after validating [policy]'s constraints with the scheduler.
     * Names are persisted in scheduler payloads, must remain stable across launches, and must match
     * `[A-Za-z0-9._-]{1,64}`. Each registration receives a fresh epoch, so an in-flight pass cannot
     * schedule a follow-up after its registration is removed.
     *
     * @throws IllegalArgumentException if the name is invalid or duplicated, the store is already
     * registered under another name, or the scheduler does not support the constraints
     * @throws IllegalStateException if this coordinator is closed
     */
    @ExperimentalStoreApi
    public fun <K : StoreKey, V : Any> register(
        name: String,
        store: MutationStore<K, V>,
        policy: DrainPolicy = DrainPolicy(),
    ): Unit {
        scheduler.validate(policy.constraints)
        registry.register(name, store, policy)
    }

    /**
     * Removes [name], completes its active [watch] with [CancellationException], cancels its tracked
     * pending activation, and suppresses follow-up scheduling from an in-flight pass of the removed
     * epoch. An unknown name is a no-op.
     *
     * @throws IllegalStateException if this coordinator is closed
     */
    @ExperimentalStoreApi
    public fun unregister(name: String): Unit {
        val registration = registry.unregister(name) ?: return
        registration.trackedActivation.value = false
        scheduler.cancel(registration.name)
        emitActivationCancelled(registration.name)
    }

    /**
     * Runs one unconditional pass for each registered store, skipping a store that is already
     * mid-pass. The unconditional drain also retries retirement-checkpoint work, which is not
     * visible through pending-write inspection. It is safe to call this repeatedly or concurrently
     * with activation passes.
     *
     * @throws IllegalStateException if this coordinator is closed
     */
    @ExperimentalStoreApi
    public suspend fun reconcile(): Unit {
        check(!registry.isClosed) { "Drain coordinator is closed." }
        registry.snapshot().forEach { registration ->
            if (!registration.passMutex.tryLock()) return@forEach
            try {
                persistSafetyActivation(registration)
                runPassLocked(registration)
            } finally {
                registration.passMutex.unlock()
            }
        }
    }

    /**
     * Watches one registered store for mutation enqueues. Collection starts before an
     * unconditional launch pass, so pending intents and retirement-checkpoint work that predates
     * the subscription are retried. Later enqueues run an in-process pass when
     * `drainOnEnqueue` is true, or schedule an activation with [Duration.ZERO] delay when it is
     * false. Other mutation events are ignored. Enqueues arriving during a pass are conflated and
     * cause at most one further pass.
     *
     * This function never returns normally. Removing the registration or closing the coordinator
     * completes it with [CancellationException].
     *
     * @throws IllegalArgumentException if [name] is not registered
     * @throws IllegalStateException if this coordinator is closed
     */
    @ExperimentalStoreApi
    public suspend fun watch(name: String): Nothing {
        val registration = requireRegistrationForWatch(name)
        coroutineScope {
            val watchJob = coroutineContext.job
            val handle = registration.job.invokeOnCompletion { watchJob.cancel() }
            try {
                val kicks = MutableStateFlow(0L)
                val subscription =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        registration.store.events.collect { event ->
                            if (event is MutationEnqueued) kicks.update { it + 1 }
                        }
                    }
                var acknowledgedKicks = kicks.value
                runPass(registration, persistSafety = true)
                while (true) {
                    acknowledgedKicks = kicks.first { it != acknowledgedKicks }
                    if (registration.policy.drainOnEnqueue) {
                        runPass(registration, persistSafety = false)
                    } else {
                        scheduleZero(registration)
                    }
                }
            } finally {
                handle.dispose()
            }
        }
        error("watch exited without cancellation")
    }

    /**
     * Runs one activation pass for [storeName]. Before draining, this persists a safety activation
     * at the policy's maximum delay. A scheduling failure emits [DrainScheduleFailed] but does not
     * prevent the pass. After draining, the safety activation is replaced by the derived follow-up
     * or cancelled when no work remains. Cancellation from the pass propagates, leaving a
     * successfully persisted safety activation as the wake-up hint.
     *
     * Passes for one name use a non-reentrant mutex. Never call this function from code already on
     * the drain stack, including `MutationServer`, mutator, conflict-policy, or `SourceOfTruth`
     * implementations, or from a watch event handler. Doing so deadlocks.
     *
     * An unknown name, a closed store, or a closed coordinator returns
     * [DrainPassOutcome.Unavailable] and emits [DrainPassFailed] instead of throwing.
     */
    @ExperimentalStoreApi
    public suspend fun runActivation(storeName: String): DrainPassOutcome {
        val registration = registry.get(storeName)
        if (registration == null) {
            val reason =
                if (registry.isClosed) {
                    "coordinator closed"
                } else {
                    "name not registered"
                }
            emitPassFailed(storeName, reason)
            return DrainPassOutcome.Unavailable(storeName = storeName, reason = reason)
        }
        return runPass(registration, persistSafety = true)
    }

    /**
     * Closes the coordinator and completes active [watch] calls with [CancellationException]. Later
     * calls to [register], [unregister], [reconcile], and [watch] throw [IllegalStateException],
     * while [runActivation] returns [DrainPassOutcome.Unavailable]. Pending scheduler activations
     * are deliberately not cancelled because they may outlive the current process. Registered
     * stores are not closed.
     */
    @ExperimentalStoreApi
    public fun close(): Unit {
        registry.close()
    }

    /**
     * Advisory scheduler lifecycle events with replay `0`, extra buffer capacity `64`, and
     * drop-oldest overflow. Emission never suspends. These events are not a scheduling or
     * settlement protocol; journal inspection remains the durable source of truth.
     */
    @ExperimentalStoreApi
    public val events: SharedFlow<DrainSchedulerEvent> = eventSink.asSharedFlow()

    internal suspend fun runPass(
        registration: DrainRegistration,
        persistSafety: Boolean,
    ): DrainPassOutcome {
        if (persistSafety) {
            persistSafetyActivation(registration)
        }
        return registration.passMutex.withLock {
            runPassLocked(registration)
        }
    }

    internal suspend fun runPassLocked(
        registration: DrainRegistration,
    ): DrainPassOutcome {
        emitActivationStarted(registration.name)

        val checkpointFailures = MutableStateFlow(false)
        try {
            coroutineScope {
                val observer =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        registration.store.events
                            .filterIsInstance<MutationCheckpointFailed>()
                            .collect { checkpointFailures.value = true }
                    }
                try {
                    registration.store.drain()
                } finally {
                    yield() // best-effort post-pass barrier for an already-buffered emission
                    observer.cancelAndJoin()
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            emitPassFailed(registration.name, failure.messageOrString())
        }
        val checkpointFailed = checkpointFailures.value

        val inspection =
            try {
                registration.store.pendingWrites() to registration.store.deadLetters().size
            } catch (failure: IllegalStateException) {
                val reason = "store closed: ${failure.messageOrString()}"
                emitPassFailed(registration.name, reason)
                return DrainPassOutcome.Unavailable(
                    storeName = registration.name,
                    reason = reason,
                )
            }
        val rows = inspection.first
        val deadLetters = inspection.second
        val derived =
            deriveFollowUp(
                rows = rows,
                checkpointFailed = checkpointFailed,
                backoff = registration.policy.backoff,
                state = registration.derivationState,
            )
        registration.derivationState = derived.nextState

        val delay = derived.delay
        if (delay == null) {
            if (registration.trackedActivation.value) {
                scheduler.cancel(registration.name)
                emitActivationCancelled(registration.name)
                registration.trackedActivation.value = false
            }
            emitPassCompleted(
                storeName = registration.name,
                pendingIntents = derived.pendingIntents,
                deadLetters = deadLetters,
            )
            return DrainPassOutcome.Cleared()
        }

        val current = registry.get(registration.name)
        if (current?.epoch != registration.epoch) {
            emitPassCompleted(
                storeName = registration.name,
                pendingIntents = derived.pendingIntents,
                deadLetters = deadLetters,
            )
            return DrainPassOutcome.Remaining(
                pendingIntents = derived.pendingIntents,
                scheduledDelay = null,
            )
        }

        try {
            scheduler.schedule(
                DrainRequest(
                    storeName = registration.name,
                    constraints = registration.policy.constraints,
                    earliestDelay = delay,
                ),
            )
            registration.trackedActivation.value = true
        } catch (failure: Throwable) {
            emitScheduleFailed(registration.name, failure.messageOrString())
            emitPassCompleted(
                storeName = registration.name,
                pendingIntents = derived.pendingIntents,
                deadLetters = deadLetters,
            )
            return DrainPassOutcome.Remaining(
                pendingIntents = derived.pendingIntents,
                scheduledDelay = null,
            )
        }
        eventSink.tryEmit(
            DrainActivationScheduled(
                storeName = registration.name,
                occurredAtEpochMillis = wallClock.nowEpochMillis(),
                delayMillis = delay.inWholeMilliseconds,
            ),
        )
        emitPassCompleted(
            storeName = registration.name,
            pendingIntents = derived.pendingIntents,
            deadLetters = deadLetters,
        )
        return DrainPassOutcome.Remaining(
            pendingIntents = derived.pendingIntents,
            scheduledDelay = delay,
        )
    }

    private fun persistSafetyActivation(registration: DrainRegistration) {
        try {
            scheduler.schedule(
                DrainRequest(
                    storeName = registration.name,
                    constraints = registration.policy.constraints,
                    earliestDelay = registration.policy.backoff.maxDelay,
                ),
            )
            registration.trackedActivation.value = true
        } catch (failure: Throwable) {
            emitScheduleFailed(registration.name, failure.messageOrString())
        }
    }

    private fun requireRegistrationForWatch(name: String): DrainRegistration {
        check(!registry.isClosed) { "Drain coordinator is closed." }
        registry.get(name)?.let { return it }
        check(!registry.isClosed) { "Drain coordinator is closed." }
        throw IllegalArgumentException("Drain registration name is not registered: $name")
    }

    private fun scheduleZero(registration: DrainRegistration) {
        try {
            scheduler.schedule(
                DrainRequest(
                    storeName = registration.name,
                    constraints = registration.policy.constraints,
                    earliestDelay = Duration.ZERO,
                ),
            )
            registration.trackedActivation.value = true
        } catch (failure: Throwable) {
            emitScheduleFailed(registration.name, failure.messageOrString())
            return
        }
        eventSink.tryEmit(
            DrainActivationScheduled(
                storeName = registration.name,
                occurredAtEpochMillis = wallClock.nowEpochMillis(),
                delayMillis = 0L,
            ),
        )
    }

    private fun emitActivationStarted(storeName: String) {
        eventSink.tryEmit(
            DrainActivationStarted(
                storeName = storeName,
                occurredAtEpochMillis = wallClock.nowEpochMillis(),
            ),
        )
    }

    private fun emitActivationCancelled(storeName: String) {
        eventSink.tryEmit(
            DrainActivationCancelled(
                storeName = storeName,
                occurredAtEpochMillis = wallClock.nowEpochMillis(),
            ),
        )
    }

    private fun emitPassCompleted(
        storeName: String,
        pendingIntents: Int,
        deadLetters: Int,
    ) {
        eventSink.tryEmit(
            DrainPassCompleted(
                storeName = storeName,
                occurredAtEpochMillis = wallClock.nowEpochMillis(),
                pendingIntents = pendingIntents,
                deadLetters = deadLetters,
            ),
        )
    }

    private fun emitPassFailed(
        storeName: String,
        message: String,
    ) {
        eventSink.tryEmit(
            DrainPassFailed(
                storeName = storeName,
                occurredAtEpochMillis = wallClock.nowEpochMillis(),
                message = message,
            ),
        )
    }

    private fun emitScheduleFailed(
        storeName: String,
        message: String,
    ) {
        eventSink.tryEmit(
            DrainScheduleFailed(
                storeName = storeName,
                occurredAtEpochMillis = wallClock.nowEpochMillis(),
                message = message,
            ),
        )
    }
}

/**
 * Creates a coordinator, attaches it to [scheduler], and uses [wallClock] to timestamp advisory
 * events. The default clock reads system time.
 */
@ExperimentalStoreApi
public fun mutationDrainCoordinator(
    scheduler: DrainScheduler,
    wallClock: WallClock = DrainSystemWallClock,
): MutationDrainCoordinator {
    val coordinator = MutationDrainCoordinator(scheduler = scheduler, wallClock = wallClock)
    scheduler.attach(coordinator)
    return coordinator
}

private fun Throwable.messageOrString(): String = message ?: toString()
