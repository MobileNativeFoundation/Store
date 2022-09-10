package com.dropbox.external.store5.concurrent

import kotlinx.coroutines.sync.Semaphore

internal class Lightswitch {
    private var counter = 0
    private val mutex = Semaphore(1)

    suspend fun lock(semaphore: Semaphore) {
        mutex.acquire()
        counter += 1
        if (counter == 1) {
            semaphore.acquire()
        }
        mutex.release()
    }

    suspend fun unlock(semaphore: Semaphore) {
        mutex.acquire()
        counter -= 1
        if (counter == 0) {
            semaphore.release()
        }
        mutex.release()
    }
}