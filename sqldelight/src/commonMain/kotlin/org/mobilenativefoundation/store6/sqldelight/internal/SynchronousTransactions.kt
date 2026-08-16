package org.mobilenativefoundation.store6.sqldelight.internal

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * Runs a suspend block to synchronous completion inside a SQLDelight transaction without
 * dispatching or parking the calling thread. The unintercepted coroutine intrinsic
 * deterministically reports the first genuine suspension. If that happens, this throws and the
 * enclosing transaction rolls back. The block receives a cancellable child job, which is cancelled
 * before a suspension failure escapes so cooperative suspension cannot later resume work outside
 * the transaction.
 */
internal fun <R> runNonSuspending(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend () -> R,
): R {
    val blockJob = Job(context[Job])
    val blockContext = (context + blockJob).rebindDriverAccessOwner(blockJob)
    try {
        blockContext.ensureActive()
        val result =
            block.startCoroutineUninterceptedOrReturn(
                Continuation(blockContext) {
                    // A completion delivered after COROUTINE_SUSPENDED belongs to a cancelled,
                    // rejected block. Its Result is deliberately ignored on the resuming thread.
                },
            )
        if (result === COROUTINE_SUSPENDED) {
            blockJob.cancel()
            context.ensureActive()
            throw IllegalStateException(
                "withTransaction block suspended while executing against a synchronous SQLDelight " +
                    "driver for this store. Keep the block to synchronous same-database statements " +
                    "(this adapter's write/delete and other SQLDelight statements); move asynchronous " +
                    "work outside withTransaction.",
            )
        }
        blockContext.ensureActive()
        @Suppress("UNCHECKED_CAST")
        return result as R
    } finally {
        blockJob.cancel()
    }
}
