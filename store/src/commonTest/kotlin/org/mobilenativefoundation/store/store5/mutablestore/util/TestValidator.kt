package org.mobilenativefoundation.store.store5.mutablestore.util

import org.mobilenativefoundation.store.store5.Validator

class TestValidator<Output : Any> : Validator<Output> {
  private val map: HashMap<Output, Boolean> = HashMap()

  fun whenever(item: Output, block: () -> Boolean) {
    map[item] = block()
  }

  override suspend fun isValid(item: Output): Boolean {
    return map[item] != false
  }
}
