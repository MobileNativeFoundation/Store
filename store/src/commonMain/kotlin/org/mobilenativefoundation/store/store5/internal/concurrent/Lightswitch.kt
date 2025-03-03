package org.mobilenativefoundation.store.store5.internal.concurrent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Locks when first reader starts and unlocks when last reader finishes. Lightswitch analogy: First
 * one into a room turns on the light (locks the mutex), and the last one out turns off the light
 * (unlocks the mutex).
 *
 * @property counter Number of readers
 */
internal class Lightswitch {
  private var counter = 0
  private val mutex = Mutex()

  suspend fun lock(room: Mutex) {
    mutex.withLock {
      counter += 1
      if (counter == 1) {
        room.lock()
      }
    }
  }

  suspend fun unlock(room: Mutex) {
    mutex.withLock {
      counter -= 1
      check(counter >= 0)
      if (counter == 0) {
        room.unlock()
      }
    }
  }
}
