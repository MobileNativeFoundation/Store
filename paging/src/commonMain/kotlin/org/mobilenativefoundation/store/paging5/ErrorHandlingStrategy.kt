package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi

@ExperimentalStoreApi
sealed interface ErrorHandlingStrategy {
    object RetryLast : ErrorHandlingStrategy

    object Ignore : ErrorHandlingStrategy

    data class Custom(val action: () -> Unit) : ErrorHandlingStrategy
}