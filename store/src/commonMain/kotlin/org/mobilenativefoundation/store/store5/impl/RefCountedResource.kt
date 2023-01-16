/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Simple holder that can ref-count items by a given key.
 */
internal class RefCountedResource<Key, T>(
    private val create: suspend (Key) -> T,
    private val onRelease: (suspend (Key, T) -> Unit)? = null
) {
    private val items = mutableMapOf<Key, Item>()
    private val lock = Mutex()

    suspend fun acquire(key: Key): T = lock.withLock {
        items.getOrPut(key) {
            Item(create(key))
        }.also {
            it.refCount++
        }.value
    }

    suspend fun release(key: Key, value: T) = lock.withLock {
        val existing = items[key]
        check(existing != null && existing.value === value) {
            "inconsistent release, seems like $value was leaked or never acquired"
        }
        existing.refCount--
        if (existing.refCount < 1) {
            items.remove(key)
            onRelease?.invoke(key, value)
        }
    }

    // used in tests
    suspend fun size() = lock.withLock {
        items.size
    }

    private inner class Item(
        val value: T,
        var refCount: Int = 0
    )
}
