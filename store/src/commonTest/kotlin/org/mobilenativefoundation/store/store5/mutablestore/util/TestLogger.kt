package org.mobilenativefoundation.store.store5.mutablestore.util

import org.mobilenativefoundation.store.store5.Logger

class TestLogger : Logger {
  val debugLogs = mutableListOf<String>()
  val errorLogs = mutableListOf<Pair<String, Throwable?>>()

  override fun debug(message: String) {
    debugLogs.add(message)
  }

  override fun error(message: String, throwable: Throwable?) {
    errorLogs.add(message to throwable)
  }
}
