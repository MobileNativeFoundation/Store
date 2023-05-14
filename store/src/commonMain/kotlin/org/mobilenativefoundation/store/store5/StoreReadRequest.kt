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
package org.mobilenativefoundation.store.store5

/**
 * data class to represent a single store request
 * @param key a unique identifier for your data
 * @param skippedCaches List of cache types that should be skipped when retuning the response see [CacheType]
 * @param refresh If set to true  [Store] will always get fresh value from fetcher while also
 *  starting the stream from the local [com.dropbox.android.external.store4.impl.SourceOfTruth] and memory cache
 *
 */
data class StoreReadRequest<out Key> private constructor(
    val key: Key,
    private val skippedCaches: Int,
    val refresh: Boolean = false,
    val fallBackToSourceOfTruth: Boolean = false
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
         * Create a [StoreReadRequest] which will skip all caches and hit your fetcher
         * (filling your caches).
         *
         * Note: If the [Fetcher] does not return any data (i.e., the returned
         * [kotlinx.coroutines.Flow], when collected, is empty). Then store will fall back to local
         * data **even** if you explicitly requested fresh data.
         * See https://github.com/dropbox/Store/pull/194 for context.
         */
        fun <Key> fresh(key: Key, fallBackToSourceOfTruth: Boolean = false) = StoreReadRequest(
            key = key,
            skippedCaches = allCaches,
            refresh = true,
            fallBackToSourceOfTruth = fallBackToSourceOfTruth
        )

        /**
         * Create a [StoreReadRequest] which will return data from memory/disk caches
         * @param refresh if true then return fetcher (new) data as well (updating your caches)
         */
        fun <Key> cached(key: Key, refresh: Boolean) = StoreReadRequest(
            key = key,
            skippedCaches = 0,
            refresh = refresh
        )

        /**
         * Create a [StoreReadRequest] which will return data from disk cache
         * @param refresh if true then return fetcher (new) data as well (updating your caches)
         */
        fun <Key> skipMemory(key: Key, refresh: Boolean) = StoreReadRequest(
            key = key,
            skippedCaches = CacheType.MEMORY.flag,
            refresh = refresh
        )

        /**
         * Creates a [StoreReadRequest] skipping all caches and returning data from network on success and data from [SourceOfTruth] on failure.
         */
        fun <Key> freshWithFallBackToSourceOfTruth(key: Key) = fresh(key, fallBackToSourceOfTruth = true)
    }
}

internal enum class CacheType(internal val flag: Int) {
    MEMORY(0b01),
    DISK(0b10)
}
