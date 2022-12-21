package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.util.TestApi
import org.mobilenativefoundation.store.store5.util.model.Note
import org.mobilenativefoundation.store.store5.util.model.NoteCommonRepresentation
import org.mobilenativefoundation.store.store5.util.model.NoteData
import org.mobilenativefoundation.store.store5.util.model.NoteNetworkRepresentation
import org.mobilenativefoundation.store.store5.util.model.NoteNetworkWriteResponse

internal class NoteApi : TestApi<String, NoteNetworkRepresentation, NoteCommonRepresentation, NoteNetworkWriteResponse> {
    internal val db = mutableMapOf<String, NoteNetworkRepresentation>()

    init {
        seed()
    }

    override fun get(key: String, fail: Boolean): NoteNetworkRepresentation? {
        if (fail) {
            throw Exception()
        }

        return db[key]
    }

    override fun post(key: String, value: NoteCommonRepresentation, fail: Boolean): NoteNetworkWriteResponse {
        if (fail) {
            throw Exception()
        }

        db[key] = NoteNetworkRepresentation(value.data)

        return NoteNetworkWriteResponse(key, true)
    }

    private fun seed() {
        db["1-id"] = NoteNetworkRepresentation(NoteData.Single(Note("1-id", "1-title", "1-content")))
        db["2-id"] = NoteNetworkRepresentation(NoteData.Single(Note("2-id", "2-title", "2-content")))
        db["3-id"] = NoteNetworkRepresentation(NoteData.Single(Note("3-id", "3-title", "3-content")))
    }
}
