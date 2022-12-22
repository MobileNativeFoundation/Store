package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.UpdaterResult
import org.mobilenativefoundation.store.store5.util.model.CommonNote
import org.mobilenativefoundation.store.store5.util.model.NotesWriteResponse

internal class NotesUpdaterProvider(private val api: NotesApi) {
    fun provide(): Updater<String, CommonNote, NotesWriteResponse> = Updater.by(
        post = { key, input ->
            val response = api.post(key, input)
            if (response.ok) {
                UpdaterResult.Success.Typed(response)
            } else {
                UpdaterResult.Error.Message("Failed to sync")
            }
        }
    )
}
