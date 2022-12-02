package org.mobilenativefoundation.store.notes.db

import org.mobilenativefoundation.store.notes.app.data.feed.RealFeed
import org.mobilenativefoundation.store.notes.android.common.api.Feed

internal object Feeds {
    fun feed(id: String): Feed = when (id) {
        "1" -> RealFeed(
            id = "1",
            notes = listOf(
                Notes.note("2")
            )
        )

        "2" -> RealFeed(
            id = "2",
            notes = listOf(
                Notes.note("1")
            )
        )

        else -> throw NotImplementedError()
    }
}