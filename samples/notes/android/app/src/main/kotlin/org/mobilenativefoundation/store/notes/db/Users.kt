package org.mobilenativefoundation.store.notes.db

import org.mobilenativefoundation.store.notes.app.data.user.RealUser
import org.mobilenativefoundation.store.notes.android.common.api.User

internal object Users {
    object Tag {
        const val ID = "1"
        const val FEED_ID = "1"
        const val NAME = "Tag Ramotar"
        const val EMAIL = "tag@mobilenativefoundation.org"
        const val AVATAR_URL = "https://i.imgur.com/TkVUZa7.jpeg"
    }

     object Trot {
        const val ID = "2"
        const val FEED_ID = "2"
        const val NAME = "Trot Ramotar"
        const val EMAIL = "trot@mobilenativefoundation.org"
        const val AVATAR_URL = "https://i.imgur.com/VHkV7Bj.png"
    }

    fun user(id: String): User = when (id) {
        "1" -> RealUser(
            id = Tag.ID,
            name = Tag.NAME,
            email = Tag.EMAIL,
            avatarUrl = Tag.AVATAR_URL,
            notes = listOf(Notes.note("2")),
            feed = Feeds.feed(Tag.FEED_ID)
        )

        "2" -> RealUser(
            id = Trot.ID,
            name = Trot.NAME,
            email = Trot.EMAIL,
            avatarUrl = Trot.AVATAR_URL,
            notes = listOf(Notes.note("1")),
            feed = Feeds.feed(Trot.FEED_ID)
        )

        else -> throw NotImplementedError()
    }
}