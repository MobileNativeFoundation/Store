package org.mobilenativefoundation.store.superstore5.util.fake

import org.mobilenativefoundation.store.superstore5.WarehouseResponse
import org.mobilenativefoundation.store.superstore5.util.TestWarehouse

class SecondaryPagesApi() : TestWarehouse<String, Page> {
    override val name: String = "SecondaryPagesApi"

    internal val db = mutableMapOf<String, Page.Data>()

    init {
        seed()
    }

    override suspend fun get(key: String): WarehouseResponse<Page> {
        val data = db[key]
        return if (data != null) {
            WarehouseResponse.Data(data, name)
        } else {
            WarehouseResponse.Empty
        }
    }

    private fun seed() {
        db["1"] = Page.Data("1")
        db["2"] = Page.Data("2")
        db["3"] = Page.Data("3")
    }
}
