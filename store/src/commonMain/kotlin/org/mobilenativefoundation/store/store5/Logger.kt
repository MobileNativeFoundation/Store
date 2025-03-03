package org.mobilenativefoundation.store.store5

/** A simple logging interface for logging error and debug messages. */
interface Logger {
  /**
   * Logs an error message, optionally with a throwable.
   *
   * @param message The error message to log.
   * @param throwable An optional [Throwable] associated with the error.
   */
  fun error(message: String, throwable: Throwable? = null)

  /**
   * Logs a debug message.
   *
   * @param message The debug message to log.
   */
  fun debug(message: String)
}
