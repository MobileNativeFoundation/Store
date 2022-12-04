package org.mobilenativefoundation.store.store5.data

import org.mobilenativefoundation.store.store5.WriteRequest
import org.mobilenativefoundation.store.store5.OnMarketCompletion
import org.mobilenativefoundation.store.store5.data.model.Note
import kotlinx.datetime.Clock

internal fun writeRequest(
    key: String,
    input: Note,
    created: Long = Clock.System.now().epochSeconds,
    onCompletions: List<OnMarketCompletion<Note>> = listOf(),
): WriteRequest<String, Note, Note> = WriteRequest.of(
    key = key,
    input = input,
    created = created,
    onCompletions = onCompletions,
)
