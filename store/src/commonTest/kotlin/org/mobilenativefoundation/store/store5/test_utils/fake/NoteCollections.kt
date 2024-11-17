package org.mobilenativefoundation.store.store5.test_utils.fake

import org.mobilenativefoundation.store.store5.test_utils.model.NoteData

internal object NoteCollections {
    object Keys {
        const val OneAndTwo = "ONE_AND_TWO"
    }

    val OneAndTwo = NoteData.Collection(listOf(Notes.One, Notes.Two))
}
