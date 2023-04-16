package org.mobilenativefoundation.store.superstore5.util.fake

import org.mobilenativefoundation.store.superstore5.util.TestApi

class PrimaryPagesApi : TestApi {

    internal val db = mutableMapOf<String, Page>()

    init {
        seed()
    }

    private fun seed() {
        db["1"] = Page.Data("1")
        db["2"] = Page.Data("2")
        db["3"] = Page.Data("3")
    }

    override suspend fun fetch(key: String, fail: Boolean, ttl: Long?): Page {
        if (fail) {
            throw Exception()
        }

        return db[key] ?: Page.Empty
    }
}