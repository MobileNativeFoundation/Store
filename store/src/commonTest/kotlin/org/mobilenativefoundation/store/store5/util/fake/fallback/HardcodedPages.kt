package org.mobilenativefoundation.store.store5.util.fake.fallback

class HardcodedPages {
    val name = "HardcodedPages"
    val db = mutableMapOf<String, Page.Data>()

    init {
        seed()
    }

    private fun seed() {
        db["1"] = Page.Data("One")
        db["2"] = Page.Data("Two")
        db["3"] = Page.Data("Three")
    }

    fun get(key: String) = db[key] ?: throw Exception()
}