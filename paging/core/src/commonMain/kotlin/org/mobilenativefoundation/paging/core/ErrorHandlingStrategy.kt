package org.mobilenativefoundation.paging.core

/**
 * Represents different strategies for handling errors during the paging process.
 */
sealed interface ErrorHandlingStrategy {
    /**
     * Ignores errors and continues with the previous state.
     */
    data object Ignore : ErrorHandlingStrategy

    /**
     * Passes the error to the UI layer for handling.
     */
    data object PassThrough : ErrorHandlingStrategy

    /**
     * Retries the last failed load operation.
     *
     * @property maxRetries The maximum number of retries before passing the error to the UI. Default is 3.
     */
    data class RetryLast(
        val maxRetries: Int = 3
    ) : ErrorHandlingStrategy
}