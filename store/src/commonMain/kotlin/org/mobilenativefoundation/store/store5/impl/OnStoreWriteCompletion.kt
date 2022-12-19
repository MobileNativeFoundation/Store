package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.StoreWriteResponse

data class OnStoreWriteCompletion<CommonRepresentation : Any>(
    val onSuccess: (StoreWriteResponse.Success<CommonRepresentation>) -> Unit,
    val onFailure: (StoreWriteResponse.Error) -> Unit
)
