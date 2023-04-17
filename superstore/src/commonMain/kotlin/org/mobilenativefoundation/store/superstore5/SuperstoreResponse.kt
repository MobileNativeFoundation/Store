package org.mobilenativefoundation.store.superstore5

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first


sealed class SuperstoreResponse<out Output : Any> {
    data class Data<out Output : Any>(
        val data: Output,
        val origin: SuperstoreResponseOrigin,
    ) : SuperstoreResponse<Output>()

    object Loading : SuperstoreResponse<Nothing>()

    object NoNewData : SuperstoreResponse<Nothing>()
}


suspend fun <Output : Any> Flow<SuperstoreResponse<Output>>.firstData() = first { it is SuperstoreResponse.Data }