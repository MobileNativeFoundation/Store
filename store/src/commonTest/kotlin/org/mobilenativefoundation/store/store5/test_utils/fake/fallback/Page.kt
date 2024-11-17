package org.mobilenativefoundation.store.store5.test_utils.fake.fallback

sealed class Page {
    data class Data(
        val title: String,
        val ttl: Long? = null,
    ) : Page()

    object Empty : Page()
}
