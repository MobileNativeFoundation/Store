package org.mobilenativefoundation.store.store5

data class OnUpdaterCompletion<NetworkWriteResponse : Any>(
    val onSuccess: (UpdaterResult.Success) -> Unit,
    val onFailure: (UpdaterResult.Error) -> Unit
)
