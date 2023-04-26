package org.mobilenativefoundation.store.store5.util.fake.fallback

class HardcodedPages {
    val name = "HardcodedPages"
    val db = mutableMapOf<String, Page.Data>()

    init {
        seed()
    }

    private fun seed() {
        db["1"] = Page.Data("1")
        db["2"] = Page.Data("2")
        db["3"] = Page.Data("3")
    }

    fun get(key: String) = db[key] ?: throw Exception()
}