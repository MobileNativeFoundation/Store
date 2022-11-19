package com.dropbox.notes.db

import com.dropbox.notes.android.app.data.note.RealNote

object Notes {
    private object One {
        const val ID = "1"
        const val TITLE = "One:Title"
        const val CONTENT = "One:Content"
    }

    val one = RealNote(id = One.ID, title = One.TITLE, content = One.CONTENT)
}