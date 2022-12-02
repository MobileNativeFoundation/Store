package org.mobilenativefoundation.store.notes.android.common.api

interface User {
    val id: String
    val name: String
    val email: String
    val avatarUrl: String
    val notes: List<Note>
    val feed: Feed
}