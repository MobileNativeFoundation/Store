package org.mobilenativefoundation.store.superstore5.util.fake

sealed class Page {
    data class Data(
        val title: String,
        val ttl: Long? = null
    ) : Page()

    object Empty : Page()
}
