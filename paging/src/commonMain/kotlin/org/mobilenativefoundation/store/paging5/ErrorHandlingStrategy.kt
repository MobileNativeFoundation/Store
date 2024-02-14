package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi

@ExperimentalStoreApi
sealed interface ErrorHandlingStrategy {
    // data class RetryLast(val maxRetries: Int) : ErrorHandlingStrategy

    object Ignore : ErrorHandlingStrategy

    object PassThrough : ErrorHandlingStrategy

    // object Custom: ErrorHandlingStrategy
}
