package org.mobilenativefoundation.store.store5

data class OnUpdaterCompletion<Response : Any>(
    val onSuccess: (UpdaterResult.Success) -> Unit,
    val onFailure: (UpdaterResult.Error<*>) -> Unit
)
