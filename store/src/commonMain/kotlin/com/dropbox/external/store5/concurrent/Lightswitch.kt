package com.dropbox.external.store5.concurrent

import kotlinx.coroutines.sync.Mutex

/**
 * Locks when first reader starts and unlocks when last reader finishes.
 * Lightswitch analogy: First one into a room turns on the light (locks the mutex), and the last one out turns off the light (unlocks the mutex).
 * @property counter Number of readers
 */
internal class Lightswitch {
    private var counter = 0
    private val mutex = Mutex()

    suspend fun lock(room: Mutex) {
        mutex.lock()
        counter += 1
        if (counter == 1) {
            room.lock()
        }
        mutex.unlock()
    }

    suspend fun unlock(room: Mutex) {
        mutex.lock()
        counter -= 1
        if (counter == 0) {
            room.unlock()
        }
        mutex.unlock()
    }
}