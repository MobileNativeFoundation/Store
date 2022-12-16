package org.mobilenativefoundation.store.notes.app.api

import com.squareup.anvil.annotations.ContributesBinding
import org.mobilenativefoundation.store.notes.android.common.api.Api
import org.mobilenativefoundation.store.notes.android.common.api.Feed
import org.mobilenativefoundation.store.notes.android.common.api.Note
import org.mobilenativefoundation.store.notes.android.common.api.User
import org.mobilenativefoundation.store.notes.android.common.scoping.AppScope
import org.mobilenativefoundation.store.notes.android.common.scoping.SingleIn
import org.mobilenativefoundation.store.notes.android.lib.result.Result
import org.mobilenativefoundation.store.notes.db.Users
import javax.inject.Inject

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RealApi @Inject constructor() : Api {
    override fun getUser(userId: String): Result<User, Throwable> = Result.asSuccess(Users.user(userId))
    override fun getFeed(userId: String): Result<Feed, Throwable> {
        TODO("Not yet implemented")
    }

    override fun getFollowers(userId: String): Result<List<User>, Throwable> {
        TODO("Not yet implemented")
    }

    override fun getFollowing(userId: String): Result<List<User>, Throwable> {
        TODO("Not yet implemented")
    }

    override fun getNotes(userId: String): Result<List<Note>, Throwable> {
        TODO("Not yet implemented")
    }

    override fun getNote(noteId: String): Result<Note, Throwable> {
        TODO("Not yet implemented")
    }

    override fun postNote(title: String, content: String): Note {
        TODO("Not yet implemented")
    }

    override fun putNote(noteId: String, title: String, content: String): Note {
        TODO("Not yet implemented")
    }

    override fun deleteNote(noteId: String): Result<Boolean, Throwable> {
        TODO("Not yet implemented")
    }

    override fun deleteAllNotes(): Result<Boolean, Throwable> {
        TODO("Not yet implemented")
    }
}