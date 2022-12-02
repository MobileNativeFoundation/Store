package org.mobilenativefoundation.store.notes.app.data.note

import org.mobilenativefoundation.store.notes.android.common.api.Note

class RealNote(
    override val id: String,
    override val title: String,
    override val content: String,
) : Note