@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.testing

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth

/** The transaction's last rollback-capable point and its first point after durable commit. */
@ExperimentalStoreApi
public enum class MutationFaultPoint {
    BeforeCommit,
    AfterCommit,
}

/**
 * One-shot callback for an adapter fixture's real storage boundary. Call [reach] inside the
 * transaction before commit and immediately after commit, before the adapter returns.
 * After-commit callbacks may cancel the caller but must not throw an injected storage failure.
 */
@ExperimentalStoreApi
public class MutationFaultInjector {
    private var armedPoint: MutationFaultPoint? = null
    private var callback: (() -> Unit)? = null

    public fun arm(point: MutationFaultPoint, action: () -> Unit) {
        check(callback == null) { "An earlier mutation fault has not fired" }
        armedPoint = point
        callback = action
    }

    public fun reach(point: MutationFaultPoint) {
        if (point != armedPoint) return
        val action = callback ?: return
        armedPoint = null
        callback = null
        action()
    }
}

/**
 * Fresh adapter with fault callbacks wired into its storage transaction, not around its public
 * mutation call. [close] releases resources after all readers have been cancelled.
 */
@ExperimentalStoreApi
public class SourceOfTruthFaultFixture<K : StoreKey, V : Any>(
    public val sourceOfTruth: SourceOfTruth<K, V>,
    public val faults: MutationFaultInjector,
    public val close: () -> Unit = {},
)

/** Fresh bookkeeper whose maintenance transactions expose actual commit boundaries. */
@ExperimentalStoreApi
public class BookkeeperFaultFixture(
    public val bookkeeper: Bookkeeper,
    public val faults: MutationFaultInjector,
    public val close: () -> Unit = {},
)

/**
 * Fresh bookkeeper with a one-shot callback in the next actual storage read made by `status`.
 * [onNextStatusRead] must preserve the adapter's failure handling and must not replace `status`.
 */
@ExperimentalStoreApi
public class BookkeeperStatusFaultFixture(
    public val bookkeeper: Bookkeeper,
    public val onNextStatusRead: (action: () -> Unit) -> Unit,
    public val close: () -> Unit = {},
)

internal data class ObservedMutationCall(
    val completion: Result<Unit>,
    val callerCancelled: Boolean,
)

/** Captures the public call's result before its enclosing cancelled coroutine finishes. */
internal suspend fun observeMutationCall(block: suspend (Job) -> Unit): ObservedMutationCall =
    coroutineScope {
        var completion: Result<Unit>? = null
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            completion = runCatching { block(currentCoroutineContext()[Job]!!) }
        }
        caller.join()
        ObservedMutationCall(checkNotNull(completion), caller.isCancelled)
    }
