package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.UpdaterResult
import org.mobilenativefoundation.store.store5.util.model.InputNote
import org.mobilenativefoundation.store.store5.util.model.NotesWriteResponse
import org.mobilenativefoundation.store.store5.util.model.OutputNote

internal class NotesUpdaterProvider(private val api: NotesApi) {
    fun provide(): Updater<NotesKey, OutputNote, NotesWriteResponse> = Updater.by(
        post = { key, input ->
            val response = api.post(key, InputNote(input.data, input.ttl ?: 0))
            if (response.ok) {
                UpdaterResult.Success.Typed(response)
            } else {
                UpdaterResult.Error("Failed to sync")
            }
        }
    )
}
