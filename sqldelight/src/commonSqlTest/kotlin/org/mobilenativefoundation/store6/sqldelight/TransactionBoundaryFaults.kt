package org.mobilenativefoundation.store6.sqldelight

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransactionWithReturn
import app.cash.sqldelight.TransactionWithoutReturn
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

internal enum class TransactionBoundary {
    BeforeCommit,
    AfterCommit,
}

/** Fires once at the outer transaction boundary; nested adapter statements share that boundary. */
internal class TransactionBoundaryFaults(
    private val delegate: Transacter,
    private val observer: (TransactionBoundary) -> Unit = {},
) : Transacter by delegate {
    private var depth = 0
    private var armedBoundary: TransactionBoundary? = null
    private var action: (() -> Unit)? = null

    fun arm(boundary: TransactionBoundary, action: () -> Unit) {
        check(this.action == null)
        armedBoundary = boundary
        this.action = action
    }

    override fun transaction(
        noEnclosing: Boolean,
        body: TransactionWithoutReturn.() -> Unit,
    ) {
        val outermost = depth++ == 0
        try {
            delegate.transaction(noEnclosing) {
                if (outermost) afterCommit { fire(TransactionBoundary.AfterCommit) }
                body()
                if (outermost) fire(TransactionBoundary.BeforeCommit)
            }
        } finally {
            depth--
        }
    }

    override fun <R> transactionWithResult(
        noEnclosing: Boolean,
        bodyWithReturn: TransactionWithReturn<R>.() -> R,
    ): R {
        val outermost = depth++ == 0
        return try {
            delegate.transactionWithResult(noEnclosing) {
                if (outermost) afterCommit { fire(TransactionBoundary.AfterCommit) }
                val result = bodyWithReturn()
                if (outermost) fire(TransactionBoundary.BeforeCommit)
                result
            }
        } finally {
            depth--
        }
    }

    private fun fire(boundary: TransactionBoundary) {
        observer(boundary)
        if (armedBoundary != boundary) return
        val callback = action ?: return
        action = null
        armedBoundary = null
        callback()
    }
}

internal data class ObservedMutationCall(
    val completion: Result<Unit>,
    val callerCancelled: Boolean,
)

/** Records the operation's result before the enclosing coroutine finishes in a cancelled state. */
internal suspend fun observeMutationCall(block: suspend (Job) -> Unit): ObservedMutationCall =
    coroutineScope {
        var completion: Result<Unit>? = null
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            completion = runCatching { block(currentCoroutineContext()[Job]!!) }
        }
        caller.join()
        ObservedMutationCall(checkNotNull(completion), caller.isCancelled)
    }
