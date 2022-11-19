package com.dropbox.notes.android.app.api

import com.dropbox.notes.android.common.entity.Api
import com.dropbox.notes.android.common.entity.User
import com.dropbox.notes.android.common.scoping.AppScope
import com.dropbox.notes.android.common.scoping.SingleIn
import com.dropbox.notes.android.lib.result.Result
import com.dropbox.notes.db.Users
import com.squareup.anvil.annotations.ContributesBinding
import javax.inject.Inject

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RealApi @Inject constructor() : Api {
    override fun getUser(): Result<User, Throwable> = Result.asSuccess(Users.tag)
}