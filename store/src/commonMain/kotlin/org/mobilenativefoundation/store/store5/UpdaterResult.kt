package org.mobilenativefoundation.store.store5

sealed class UpdaterResult {

    sealed class Success : UpdaterResult() {
        data class Typed<Response : Any>(val value: Response) : Success()
        data class Untyped(val value: Any) : Success()
    }

    data class Error<E: Any>(val error: E) : UpdaterResult()
}
