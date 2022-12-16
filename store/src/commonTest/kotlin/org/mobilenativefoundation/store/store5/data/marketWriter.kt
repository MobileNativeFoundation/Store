package org.mobilenativefoundation.store.store5.data

import kotlinx.datetime.Clock
import org.mobilenativefoundation.store.store5.OnMarketCompletion
import org.mobilenativefoundation.store.store5.WriteRequest
import org.mobilenativefoundation.store.store5.data.model.Note
import org.mobilenativefoundation.store.store5.data.model.NoteCommonRepresentation
import org.mobilenativefoundation.store.store5.data.model.NoteMarketKey

internal fun writeRequest(
    key: String,
    input: Note,
    created: Long = Clock.System.now().epochSeconds,
    onCompletions: List<OnMarketCompletion<Note>> = listOf(),
): WriteRequest<String, Note> = WriteRequest.of(
    key = key,
    input = input,
    created = created,
    onCompletions = onCompletions,
)

internal fun complexWriteRequest(
    key: NoteMarketKey,
    input: NoteCommonRepresentation,
    created: Long = Clock.System.now().epochSeconds,
    onCompletions: List<OnMarketCompletion<NoteCommonRepresentation>> = listOf(),
): WriteRequest<NoteMarketKey, NoteCommonRepresentation> = WriteRequest.of(
    key = key,
    input = input,
    created = created,
    onCompletions = onCompletions,
)
