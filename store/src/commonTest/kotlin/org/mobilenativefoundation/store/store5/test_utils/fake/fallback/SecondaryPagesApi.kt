package org.mobilenativefoundation.store.store5.test_utils.fake.fallback

class SecondaryPagesApi() {
    val name: String = "SecondaryPagesApi"
    internal val db = mutableMapOf<String, Page.Data>()

    init {
        seed()
    }

    fun get(key: String) = db[key] ?: throw Exception()

    private fun seed() {
        db["1"] = Page.Data("1")
        db["2"] = Page.Data("2")
        db["3"] = Page.Data("3")
    }
}
