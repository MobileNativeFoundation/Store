package org.mobilenativefoundation.store.store5.market.data

import kotlinx.datetime.Clock
import org.mobilenativefoundation.store.store5.market.OnMarketCompletion
import org.mobilenativefoundation.store.store5.market.WriteRequest
import org.mobilenativefoundation.store.store5.market.data.model.Note
import org.mobilenativefoundation.store.store5.market.data.model.NoteCommonRepresentation
import org.mobilenativefoundation.store.store5.market.data.model.NoteMarketKey

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
