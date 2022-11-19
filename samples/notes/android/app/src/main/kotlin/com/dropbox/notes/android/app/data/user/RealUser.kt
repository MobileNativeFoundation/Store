package com.dropbox.notes.android.app.data.user

import com.dropbox.notes.android.common.entity.Note
import com.dropbox.notes.android.common.entity.User

class RealUser(
    override val id: String,
    override val name: String,
    override val email: String,
    override val avatarUrl: String,
    override val notes: List<Note>
) : User