package org.mobilenativefoundation.store.notes.db

import org.mobilenativefoundation.store.notes.android.common.api.Note
import org.mobilenativefoundation.store.notes.app.data.note.RealNote

internal object Notes {
    private object One {
        const val ID = "1"
        const val TITLE = "Title 1"
        const val CONTENT = "Content 1"
    }

    private object Two {
        const val ID = "2"
        const val TITLE = "Title 2"
        const val CONTENT = "Content 2"
    }

    fun note(id: String): Note = when (id) {
        "1" -> RealNote(One.ID, One.TITLE, One.CONTENT)
        "2" -> RealNote(Two.ID, Two.TITLE, Two.CONTENT)
        else -> throw NotImplementedError()
    }
}