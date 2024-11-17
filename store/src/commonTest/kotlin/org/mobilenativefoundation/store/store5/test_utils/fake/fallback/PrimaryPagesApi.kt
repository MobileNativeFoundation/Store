package org.mobilenativefoundation.store.store5.test_utils.fake.fallback

class PrimaryPagesApi {
    val name = "PrimaryPagesApi"

    internal val db = mutableMapOf<String, Page>()

    init {
        seed()
    }

    private fun seed() {
        db["1"] = Page.Data("1")
        db["2"] = Page.Data("2")
        db["3"] = Page.Data("3")
    }

    fun fetch(
        key: String,
        fail: Boolean,
        ttl: Long?,
    ): Page {
        if (fail) {
            throw Exception()
        }

        return db[key] ?: Page.Empty
    }
}
