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
package com.dropbox.android.external.store4

data class StoreRequest<Key> private constructor(
    /**
     * The key for the request
     */
    val key: Key,
    /**
     * List of cache types that should be skipped when retuning the response
     */
    private val skippedCaches: Int,
    /**
     * If set to with stream requests, Store will always get fresh value from fetcher while also
     * starting the stream from the local data (disk and/or memory cache)
     */
    val refresh: Boolean = false
) {

    internal fun shouldSkipCache(type: CacheType) = skippedCaches.and(type.flag) != 0

    companion object {
        private val allCaches = CacheType.values().fold(0) { prev, next ->
            prev.or(next.flag)
        }

        // TODO figure out if any of these helper methods make sense
        fun <Key> fresh(key: Key) = StoreRequest(
                key = key,
                skippedCaches = allCaches,
                refresh = true
        )

        fun <Key> cached(key: Key, refresh: Boolean) = StoreRequest(
                key = key,
                skippedCaches = 0,
                refresh = refresh
        )

        fun <Key> skipMemory(key: Key, refresh: Boolean) = StoreRequest(
                key = key,
                skippedCaches = CacheType.MEMORY.flag,
                refresh = refresh
        )
    }
}

internal enum class CacheType(internal val flag: Int) {
    MEMORY(0b01),
    DISK(0b10)
}
