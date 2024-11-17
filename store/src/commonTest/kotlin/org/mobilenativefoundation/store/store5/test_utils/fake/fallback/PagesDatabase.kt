package org.mobilenativefoundation.store.store5.test_utils.fake.fallback

class PagesDatabase {
    private val db: MutableMap<String, Page?> = mutableMapOf()

    fun put(
        key: String,
        input: Page,
    ): Boolean {
        db[key] = input
        return true
    }

    fun get(key: String): Page? = db[key]
}
