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
 * Holder for responses from Store.
 *
 * Instead of using regular error channels (a.k.a. throwing exceptions), Store uses this holder
 * class to represent each response. This allows the flow to keep running even if an error happens
 * so that if there is an observable single source of truth, application can keep observing it.
 */
sealed class StoreResponse<out DATA, out ERROR> {
    /**
     * Represents the source of the Response.
     */
    abstract val origin: ResponseOrigin

    /**
     * Loading event dispatched by [Store] to signal the [Fetcher] is in progress.
     */
    data class Loading(override val origin: ResponseOrigin) : StoreResponse<Nothing, Nothing>()

    /**
     * Data dispatched by [Store]
     */
    data class Data<T>(val value: T, override val origin: ResponseOrigin) : StoreResponse<T, Nothing>()

    /**
     * No new data event dispatched by Store to signal the [Fetcher] returned no data (i.e the
     * returned [kotlinx.coroutines.Flow], when collected, was empty).
     */
    data class NoNewData(override val origin: ResponseOrigin) : StoreResponse<Nothing, Nothing>()

    /**
     * Error dispatched by a pipeline
     */
    data class Error<ERROR>(val error: ERROR, override val origin: ResponseOrigin) : StoreResponse<Nothing, ERROR>()

    /**
     * Returns the available data or throws [NullPointerException] if there is no data.
     */
    fun requireData(): DATA {
        if (this is Data) {
            return value
        }
        if (this is Error && error is Throwable) {
            throw error
        }
        throw NullPointerException("there is no data in $this")
    }
    /**
     * If there is data available, returns it; otherwise returns null.
     */
    fun dataOrNull(): DATA? = when (this) {
        is Data -> value
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <R> swapType(): StoreResponse<R, ERROR> = when (this) {
        is Error -> this
        is Loading -> this
        is NoNewData -> this
        is Data -> throw RuntimeException("cannot swap type for StoreResponse.Data")
    }
}

/**
 * Represents the origin for a [StoreResponse].
 */
enum class ResponseOrigin {
    /**
     * [StoreResponse] is sent from the cache
     */
    Cache,

    /**
     * [StoreResponse] is sent from the persister
     */
    SourceOfTruth,

    /**
     * [StoreResponse] is sent from a fetcher,
     */
    Fetcher
}
