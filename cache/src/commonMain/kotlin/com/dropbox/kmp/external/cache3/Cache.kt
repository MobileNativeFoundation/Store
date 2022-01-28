/*
 * Copyright 2022 André Claßen
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

package com.dropbox.kmp.external.cache3

interface Cache<in K : Any, V : Any> {
    /**
     * Returns the value associated with `key` in this cache, or `null` if there is no
     * cached value for `key`.
     *
     * @since 11.0
     */
    fun getIfPresent(key: K): V?

    fun getOrPut(key: K, defaultValue: () -> V): V

    /**
     * Associates `value` with `key` in this cache. If the cache previously contained a
     * value associated with `key`, the old value is replaced by `value`.
     *
     *
     * Prefer [.get] when using the conventional "if cached, return;
     * otherwise create, cache and return" pattern.
     *
     * @since 11.0
     */
    fun put(key: K, value: V)

    /**
     * Discards any cached value for key `key`.
     */
    fun invalidate(key: K)

    /**
     * Discards all entries in the cache.
     */
    fun invalidateAll()
}
