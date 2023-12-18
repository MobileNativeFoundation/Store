package org.mobilenativefoundation.store.store5

data class OnFetcherCompletion<Network : Any>(
    val onSuccess: (FetcherResult.Data<Network>) -> Unit,
    val onFailure: (FetcherResult.Error<*>) -> Unit
)
