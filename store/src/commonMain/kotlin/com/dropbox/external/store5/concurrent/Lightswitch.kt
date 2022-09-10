package com.dropbox.external.store5.concurrent

import kotlinx.coroutines.sync.Mutex

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