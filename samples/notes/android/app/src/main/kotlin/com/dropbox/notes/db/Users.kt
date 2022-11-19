package com.dropbox.notes.db

import com.dropbox.notes.android.app.data.user.RealUser

object Users {
    private object Tag {
        const val ID = "1"
        const val NAME = "Tag Ramotar"
        const val EMAIL = "tag@dropbox.com"
        const val AVATAR_URL = "https://imgur.com/TkVUZa7"
        val notes = listOf(Notes.one)
    }

    val tag = RealUser(id = Tag.ID, name = Tag.NAME, email = Tag.EMAIL, avatarUrl = Tag.AVATAR_URL, notes = Tag.notes)
}