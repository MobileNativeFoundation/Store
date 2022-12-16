package org.mobilenativefoundation.store.notes.android.common.api

import org.mobilenativefoundation.store.notes.android.lib.result.Result

interface Api {
    fun getUser(userId: String): Result<User, Throwable>
    fun getFeed(userId: String): Result<Feed, Throwable>
    fun getFollowers(userId: String): Result<List<User>, Throwable>
    fun getFollowing(userId: String): Result<List<User>, Throwable>
    fun getNotes(userId: String): Result<List<Note>, Throwable>
    fun getNote(noteId: String): Result<Note, Throwable>
    fun postNote(title: String, content: String): Note
    fun putNote(noteId: String, title: String, content: String): Note
    fun deleteNote(noteId: String): Result<Boolean, Throwable>
    fun deleteAllNotes(): Result<Boolean, Throwable>
}