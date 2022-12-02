package org.mobilenativefoundation.store.notes.app.data.feed

import org.mobilenativefoundation.store.notes.android.common.api.Feed
import org.mobilenativefoundation.store.notes.android.common.api.Note

class RealFeed(
    override val id: String,
    override val notes: List<Note>
) : Feed