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

/**
 * data class to represent a single store request
 * @param key a unique identifier for your data
 * @param skippedCaches List of cache types that should be skipped when retuning the response see [CacheType]
 * @param refresh If set to true  [Store] will always get fresh value from fetcher while also
 *  starting the stream from the local [com.dropbox.android.external.store4.impl.SourceOfTruth] and memory cache
 *
 */
data class StoreRequest<Key> private constructor(

    val key: Key,

    private val skippedCaches: Int,

    val refresh: Boolean = false
) {

    internal fun shouldSkipCache(type: CacheType) = skippedCaches.and(type.flag) != 0

    /**
     * Factories for common store requests
     */
    companion object {
        private val allCaches = CacheType.values().fold(0) { prev, next ->
            prev.or(next.flag)
        }

        /**
         * Create a Store Request which will skip all caches and hit your fetcher
         * (filling your caches).
         *
         * Note: If the [Fetcher] does not return any data (i.e the returned
         * [kotlinx.coroutines.Flow], when collected, is empty). Then store will fall back to local
         * data **even** if you explicitly requested fresh data.
         * See https://github.com/dropbox/Store/pull/194 for context.
         */
        fun <Key> fresh(key: Key) = StoreRequest(
                key = key,
                skippedCaches = allCaches,
                refresh = true
        )
        /**
         * Create a Store Request which will return data from memory/disk caches
         * @param refresh if true then return fetcher (new) data as well (updating your caches)
         */
        fun <Key> cached(key: Key, refresh: Boolean) = StoreRequest(
                key = key,
                skippedCaches = 0,
                refresh = refresh
        )
        /**
         * Create a Store Request which will return data from disk cache
         * @param refresh if true then return fetcher (new) data as well (updating your caches)
         */
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
