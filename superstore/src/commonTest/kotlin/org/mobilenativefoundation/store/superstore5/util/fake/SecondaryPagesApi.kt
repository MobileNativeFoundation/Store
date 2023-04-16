package org.mobilenativefoundation.store.superstore5.util.fake

import org.mobilenativefoundation.store.superstore5.util.TestWarehouse

class SecondaryPagesApi() : TestWarehouse<String, Page> {

    internal val db = mutableMapOf<String, Page.Data>()

    init {
        seed()
    }

    override suspend fun get(key: String): Page = db[key] ?: Page.Empty

    private fun seed() {
        db["1"] = Page.Data("1")
        db["2"] = Page.Data("2")
        db["3"] = Page.Data("3")
    }

    override suspend fun get(key: String, fail: Boolean, ttl: Long?): Page {
        if (fail) {
            throw Exception()
        }

        val page = get(key)
        return if (ttl != null && page is Page.Data) {
            page.copy(ttl = ttl)
        } else {
            page
        }
    }

}