package org.mobilenativefoundation.store.notes.app.data.user

import org.mobilenativefoundation.store.notes.android.common.api.Feed
import org.mobilenativefoundation.store.notes.android.common.api.Note
import org.mobilenativefoundation.store.notes.android.common.api.User

class RealUser(
    override val id: String,
    override val name: String,
    override val email: String,
    override val avatarUrl: String,
    override val notes: List<Note>,
    override val feed: Feed
) : User