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
 * Holder for responses from Store.
 *
 * Instead of using regular error channels (a.k.a. throwing exceptions), Store uses this holder
 * class to represent each response. This allows the flow to keep running even if an error happens
 * so that if there is an observable single source of truth, application can keep observing it.
 */
sealed class StoreReadResponse<out Output> {
    /**
     * Represents the source of the Response.
     */
    abstract val origin: StoreReadResponseOrigin

    object Initial : StoreReadResponse<Nothing>() {
        override val origin: StoreReadResponseOrigin = StoreReadResponseOrigin.Initial
    }

    /**
     * Loading event dispatched by [Store] to signal the [Fetcher] is in progress.
     */
    data class Loading(override val origin: StoreReadResponseOrigin) : StoreReadResponse<Nothing>()

    /**
     * Data dispatched by [Store]
     */
    data class Data<Output>(val value: Output, override val origin: StoreReadResponseOrigin) :
        StoreReadResponse<Output>()

    /**
     * No new data event dispatched by Store to signal the [Fetcher] returned no data (i.e., the
     * returned [kotlinx.coroutines.flow.Flow], when collected, was empty).
     */
    data class NoNewData(override val origin: StoreReadResponseOrigin) : StoreReadResponse<Nothing>()

    /**
     * Error dispatched by a pipeline
     */
    sealed class Error : StoreReadResponse<Nothing>() {
        data class Exception(
            val error: Throwable,
            override val origin: StoreReadResponseOrigin,
        ) : Error()

        data class Message(
            val message: String,
            override val origin: StoreReadResponseOrigin,
        ) : Error()

        data class Custom<E : Any>(
            val error: E,
            override val origin: StoreReadResponseOrigin,
        ) : Error()
    }

    /**
     * Returns the available data or throws [NullPointerException] if there is no data.
     */
    fun requireData(): Output {
        return when (this) {
            is Data -> value
            is Error -> throw this.doThrow()
            else -> throw NullPointerException("there is no data in $this")
        }
    }

    /**
     * If this [StoreReadResponse] is of type [StoreReadResponse.Error], throws the exception
     * Otherwise, does nothing.
     */
    fun throwIfError() {
        if (this is Error) {
            throw this.doThrow()
        }
    }

    /**
     * If this [StoreReadResponse] is of type [StoreReadResponse.Error], returns the available error
     * from it. Otherwise, returns `null`.
     */
    fun errorMessageOrNull(): String? {
        return when (this) {
            is Error.Message -> message
            is Error.Exception -> error.message ?: "exception: ${error::class}"
            else -> null
        }
    }

    /**
     * If there is data available, returns it; otherwise returns null.
     */
    fun dataOrNull(): Output? =
        when (this) {
            is Data -> value
            else -> null
        }

    private fun errorOrNull(): Throwable? {
        if (this is Error.Exception) {
            return error
        }

        return null
    }

    /**
     * @returns Error if there is one, else null.
     */
    @Suppress("UNCHECKED_CAST")
    fun <E : Any> errorOrNull(): E? {
        if (this is Error.Custom<*>) {
            return (this as? Error.Custom<E>)?.error
        }

        return errorOrNull() as? E
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <T> swapType(): StoreReadResponse<T> =
        when (this) {
            is Error -> this
            is Loading -> this
            is NoNewData -> this
            is Data -> throw RuntimeException("cannot swap type for StoreResponse.Data")
            is Initial -> this
        }
}

/**
 * Represents the origin for a [StoreReadResponse].
 */
sealed class StoreReadResponseOrigin {
    /**
     * [StoreReadResponse] is sent from the cache
     */
    object Cache : StoreReadResponseOrigin()

    /**
     * [StoreReadResponse] is sent from the persister
     */
    object SourceOfTruth : StoreReadResponseOrigin()

    /**
     * [StoreReadResponse] is sent from a fetcher
     * @property name Unique name to enable differentiation when [org.mobilenativefoundation.store.store5.Fetcher.fallback] exists
     */
    data class Fetcher(val name: String? = null) : StoreReadResponseOrigin()

    object Initial : StoreReadResponseOrigin()
}

fun StoreReadResponse.Error.doThrow(): Throwable {
    return when (this) {
        is StoreReadResponse.Error.Exception -> error
        is StoreReadResponse.Error.Message -> RuntimeException(message)
        is StoreReadResponse.Error.Custom<*> -> {
            if (error is Throwable) {
                error
            } else {
                RuntimeException("Non-throwable custom error: $error")
            }
        }
    }
}
