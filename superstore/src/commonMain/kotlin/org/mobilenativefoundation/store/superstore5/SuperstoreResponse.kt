package org.mobilenativefoundation.store.superstore5


sealed class SuperstoreResponse<out Output : Any> {
    data class Data<out Output : Any>(
        val data: Output,
        val origin: SuperstoreResponseOrigin,
    ) : SuperstoreResponse<Output>()

    object Loading : SuperstoreResponse<Nothing>()
}