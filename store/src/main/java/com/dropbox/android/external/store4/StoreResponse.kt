package com.dropbox.android.external.store4

/**
 * Holder for responses from Store.
 *
 * Instead of using regular error channels (a.k.a. throwing exceptions), Store uses this holder
 * class to represent each response. This allows the flow to keep running even if an error happens
 * so that if there is an observable single source of truth, application can keep observing it.
 */
sealed class StoreResponse<T> {
    /**
     * Represents the source of the Response.
     */
    abstract val origin: ResponseOrigin

    /**
     * Loading event dispatched by a Pipeline
     */
    data class Loading<T>(override val origin: ResponseOrigin) : StoreResponse<T>()

    /**
     * Data dispatched by a pipeline
     */
    data class Data<T>(val value: T, override val origin: ResponseOrigin) : StoreResponse<T>()

    /**
     * Error dispatched by a pipeline
     */
    data class Error<T>(val error: Throwable, override val origin: ResponseOrigin) :
            StoreResponse<T>()

    /**
     * Returns the available data or throws [NullPointerException] if there is no data.
     */
    fun requireData(): T {
        return when (this) {
            is Data -> value
            is Error -> throw error
            else -> throw NullPointerException("there is no data in $this")
        }
    }

    /**
     * If this [StoreResponse] is of type [StoreResponse.Error], throws the exception
     * Otherwise, does nothing.
     */
    fun throwIfError() {
        if (this is Error) {
            throw error
        }
    }

    /**
     * If this [StoreResponse] is of type [StoreResponse.Error], returns the available error
     * from it. Otherwise, returns `null`.
     */
    fun errorOrNull(): Throwable? {
        return (this as? Error)?.error
    }

    /**
     * If there is data available, returns it; otherwise returns null.
     */
    fun dataOrNull(): T? = when (this) {
        is Data -> value
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <R> swapType(): StoreResponse<R> = when (this) {
        is Error -> Error(error, origin)
        is Loading -> Loading(origin)
        is Data -> Data(value = value as R, origin = origin)
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
    Persister,
    /**
     * [StoreResponse] is sent from a fetcher,
     */
    Fetcher
}
