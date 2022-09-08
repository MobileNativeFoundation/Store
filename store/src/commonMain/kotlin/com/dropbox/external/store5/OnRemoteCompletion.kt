package com.dropbox.external.store5

data class OnRemoteCompletion<T : Any>(
    val onSuccess: (RemoteResult.Success<T>) -> Unit,
    val onFailure: (RemoteResult.Failure) -> Unit
)