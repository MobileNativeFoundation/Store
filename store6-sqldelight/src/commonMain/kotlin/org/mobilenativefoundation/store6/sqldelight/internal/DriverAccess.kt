package org.mobilenativefoundation.store6.sqldelight.internal

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Serializes access to synchronous SQLDelight drivers without retaining driver instances. Drivers
 * map onto a bounded, power-of-two set of mutexes; a collision can only over-serialize unrelated
 * drivers. A live context lease makes same-driver adapter calls reentrant inside
 * `withTransaction`, while job and execution-thread identity prevent launched or concurrently
 * resumed continuations from escaping that lease.
 */
internal class DriverAccess private constructor(
    private val stripe: Int,
) {
    suspend fun <R> withAccess(block: suspend () -> R): R {
        val context = currentCoroutineContext()
        val ownerJob = context[Job]
        val callingThreadId = currentExecutionThreadId()
        val heldAccess = context[HeldDriverAccess]
        if (heldAccess?.owns(stripe, ownerJob, callingThreadId) == true) {
            context.ensureActive()
            return block()
        }

        val gate = gates[stripe]
        gate.lock()
        val lease = Job()
        return try {
            context.ensureActive()
            val acquiredThreadId = currentExecutionThreadId()
            withContext(
                HeldDriverAccess(
                    stripe = stripe,
                    ownerJob = ownerJob,
                    ownerThreadId = acquiredThreadId,
                    lease = lease,
                    parent = heldAccess,
                ),
            ) {
                block()
            }
        } finally {
            lease.cancel()
            gate.unlock()
        }
    }

    companion object {
        private const val STRIPE_COUNT = 64
        private val gates = List(STRIPE_COUNT) { Mutex() }

        fun forDriver(driver: SqlDriver): DriverAccess =
            DriverAccess(driver.hashCode() and (STRIPE_COUNT - 1))
    }
}

/** Rebinds live access leases to the child job used by a synchronous transaction block. */
internal fun CoroutineContext.rebindDriverAccessOwner(ownerJob: Job): CoroutineContext {
    val heldAccess = this[HeldDriverAccess] ?: return this
    return this + heldAccess.withOwner(ownerJob)
}

private class HeldDriverAccess(
    private val stripe: Int,
    private val ownerJob: Job?,
    private val ownerThreadId: Long,
    private val lease: Job,
    private val parent: HeldDriverAccess?,
) : AbstractCoroutineContextElement(HeldDriverAccess) {
    fun owns(
        candidateStripe: Int,
        candidateOwnerJob: Job?,
        candidateThreadId: Long,
    ): Boolean {
        var candidate: HeldDriverAccess? = this
        while (candidate != null) {
            if (
                candidate.stripe == candidateStripe &&
                candidate.ownerJob === candidateOwnerJob &&
                candidate.ownerThreadId == candidateThreadId &&
                candidate.lease.isActive
            ) {
                return true
            }
            candidate = candidate.parent
        }
        return false
    }

    fun withOwner(ownerJob: Job): HeldDriverAccess =
        HeldDriverAccess(
            stripe = stripe,
            ownerJob = ownerJob,
            ownerThreadId = ownerThreadId,
            lease = lease,
            parent = parent?.withOwner(ownerJob),
        )

    companion object Key : CoroutineContext.Key<HeldDriverAccess>
}
