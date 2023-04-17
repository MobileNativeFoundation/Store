package org.mobilenativefoundation.store.superstore5.util.fake

class PagesDatabase {
    private val db: MutableMap<String, Page?> = mutableMapOf()

    fun put(key: String, input: Page): Boolean {
        db[key] = input
        return true
    }

    fun get(key: String): Page? = db[key]
}
