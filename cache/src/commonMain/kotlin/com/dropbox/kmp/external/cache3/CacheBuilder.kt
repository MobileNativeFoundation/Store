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

import kotlin.time.Duration

@DslMarker
annotation class CacheBuilderDsl

fun <K : Any, V : Any> cacheBuilder(lambda: CacheBuilder<K, V>.() -> Unit) =
    CacheBuilder<K, V>().apply(lambda).build()

@CacheBuilderDsl
class CacheBuilder<K : Any, V : Any> {
    internal var concurrencyLevel = 4
        private set
    internal val initialCapacity = 16
    internal var maximumSize = UNSET_LONG
        private set
    internal var maximumWeight = UNSET_LONG
        private set
    internal var expireAfterAccess: Duration = Duration.INFINITE
        private set
    internal var expireAfterWrite: Duration = Duration.INFINITE
        private set
    internal var weigher: Weigher<K, V>? = null
        private set
    internal var ticker: Ticker? = null
        private set

    fun concurrencyLevel(lambda: () -> Int) {
        concurrencyLevel = lambda()
    }

    fun maximumSize(lambda: () -> Long) {
        maximumSize = lambda()
            .apply {
                if (this < 0) throw IllegalArgumentException("maximum size must not be negative")
            }
        maximumSize = lambda()
    }

    fun expireAfterAccess(lambda: () -> Duration) {
        expireAfterAccess = lambda().apply {
            if (this.isNegative()) throw IllegalArgumentException("expireAfterAccess duration must be positive")
        }
    }

    fun expireAfterWrite(lambda: () -> Duration) {
        expireAfterWrite = lambda().apply {
            if (this.isNegative()) throw IllegalArgumentException("expireAfterWrite duration must be positive")
        }
    }

    fun ticker(lambda: () -> Ticker) {
        ticker = lambda()
    }

    fun weigher(maximumWeight: Long, weigher: Weigher<K, V>) {
        this.maximumWeight = maximumWeight.apply {
            if (this < 0) throw IllegalArgumentException("maximum weight must not be negative")
        }
        this.weigher = weigher
    }

    fun build(): Cache<K, V> {
        if (maximumSize != -1L && weigher != null) {
            throw IllegalStateException("maximum size can not be combined with weigher")
        }
        return LocalCache.LocalManualCache(this)
    }

    companion object {
        private const val UNSET_LONG = -1L
    }
}
