package org.mobilenativefoundation.store.store5

data class OnNetworkCompletion<T : Any>(
    val onSuccess: (NetworkResult.Success<T>) -> Unit,
    val onFailure: (NetworkResult.Failure) -> Unit
)
