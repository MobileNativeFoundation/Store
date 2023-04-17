package org.mobilenativefoundation.store.superstore5


sealed class WarehouseResponse<out Output : Any> {
    data class Data<Output : Any>(
        val data: Output,
        val origin: String
    ) : WarehouseResponse<Output>()

    object Empty : WarehouseResponse<Nothing>()
}