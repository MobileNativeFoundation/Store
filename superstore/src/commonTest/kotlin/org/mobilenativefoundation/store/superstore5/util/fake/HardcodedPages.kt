package org.mobilenativefoundation.store.superstore5.util.fake

import org.mobilenativefoundation.store.superstore5.Warehouse

class HardcodedPages : Warehouse<String, Page.Data> {
    internal val db = mutableMapOf<String, Page.Data>()

    init {
        seed()
    }

    private fun seed() {
        db["1"] = Page.Data("One")
        db["2"] = Page.Data("Two")
        db["3"] = Page.Data("Three")
    }

    override suspend fun get(key: String): Page.Data = db[key]!!
}