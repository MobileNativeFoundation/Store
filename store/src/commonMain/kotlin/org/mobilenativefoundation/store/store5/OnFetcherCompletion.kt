package org.mobilenativefoundation.store.store5

data class OnFetcherCompletion<NetworkRepresentation : Any>(
    val onSuccess: (FetcherResult.Data<NetworkRepresentation>) -> Unit,
    val onFailure: (FetcherResult.Error) -> Unit
)