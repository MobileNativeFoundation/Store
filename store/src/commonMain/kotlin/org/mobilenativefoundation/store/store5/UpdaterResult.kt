package org.mobilenativefoundation.store.store5

sealed class UpdaterResult {
  sealed class Success : UpdaterResult() {
    data class Typed<Response : Any>(val value: Response) : Success()

    data class Untyped(val value: Any) : Success()
  }

  sealed class Error : UpdaterResult() {
    data class Exception(val error: Throwable) : Error()

    data class Message(val message: String) : Error()
  }
}
