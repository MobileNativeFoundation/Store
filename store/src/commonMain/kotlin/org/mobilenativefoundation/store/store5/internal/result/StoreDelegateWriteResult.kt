package org.mobilenativefoundation.store.store5.internal.result

sealed class StoreDelegateWriteResult {
    object Success : StoreDelegateWriteResult()

    sealed class Error : StoreDelegateWriteResult() {
        data class Message(val error: String) : Error()

        data class Exception(val error: Throwable) : Error()
    }
}
