package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.util.model.NoteData

internal object NoteCollections {
    object Keys {
        const val OneAndTwo = "ONE_AND_TWO"
    }

    val OneAndTwo = NoteData.Collection(listOf(Notes.One, Notes.Two))
}
