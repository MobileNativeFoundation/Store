package org.mobilenativefoundation.store.notes.android.common.api

interface Feed {
    val id: String
    val notes: List<Note>
}