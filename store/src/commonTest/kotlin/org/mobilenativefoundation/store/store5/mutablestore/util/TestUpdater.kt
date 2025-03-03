package org.mobilenativefoundation.store.store5.mutablestore.util

import org.mobilenativefoundation.store.store5.OnUpdaterCompletion
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.UpdaterResult

class TestUpdater<Key : Any, Output : Any, Response : Any> : Updater<Key, Output, Response> {
  var exception: Throwable? = null
  var errorMessage: String? = null
  var successValue: Response? = null

  override suspend fun post(key: Key, value: Output): UpdaterResult {
    exception?.let {
      return UpdaterResult.Error.Exception(it)
    }
    errorMessage?.let {
      return UpdaterResult.Error.Message(it)
    }
    successValue?.let {
      return UpdaterResult.Success.Typed(it)
    }
    return UpdaterResult.Success.Untyped(value)
  }

  override val onCompletion: OnUpdaterCompletion<Response>? = null
}
