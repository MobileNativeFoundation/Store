package com.dropbox.notes.android.app.data.note

import com.dropbox.notes.android.common.entity.Note

class RealNote(
    override val id: String,
    override val title: String,
    override val content: String,
) : Note