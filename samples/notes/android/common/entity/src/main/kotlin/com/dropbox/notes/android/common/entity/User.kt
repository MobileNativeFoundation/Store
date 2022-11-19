package com.dropbox.notes.android.common.entity

interface User {
    val id: String
    val name: String
    val email: String
    val avatarUrl: String
    val notes: List<Note>
}