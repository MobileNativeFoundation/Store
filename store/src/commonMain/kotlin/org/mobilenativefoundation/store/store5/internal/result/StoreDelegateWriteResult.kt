package org.mobilenativefoundation.store.store5.internal.result

sealed class StoreDelegateWriteResult {
    object Success : StoreDelegateWriteResult()
    data class Error<E : Any>(val error: E) : StoreDelegateWriteResult()
}
