package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.StoreWriteResponse

data class OnStoreWriteCompletion(
    val onSuccess: (StoreWriteResponse.Success) -> Unit,
    val onFailure: (StoreWriteResponse.Error<*>) -> Unit
)
