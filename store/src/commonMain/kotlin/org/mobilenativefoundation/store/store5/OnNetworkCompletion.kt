package org.mobilenativefoundation.store.store5

data class OnNetworkCompletion<T : Any>(
    val onSuccess: (NetworkReadResult.Success<T>) -> Unit,
    val onFailure: (NetworkReadResult.Failure) -> Unit
)
