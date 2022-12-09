package org.mobilenativefoundation.store.store5.data

import kotlinx.datetime.Clock
import org.mobilenativefoundation.store.store5.OnMarketCompletion
import org.mobilenativefoundation.store.store5.WriteRequest
import org.mobilenativefoundation.store.store5.data.model.Note
import org.mobilenativefoundation.store.store5.data.model.NoteMarketInput
import org.mobilenativefoundation.store.store5.data.model.NoteMarketKey
import org.mobilenativefoundation.store.store5.data.model.NoteMarketOutput

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

internal fun complexWriteRequest(
    key: NoteMarketKey,
    input: NoteMarketInput,
    created: Long = Clock.System.now().epochSeconds,
    onCompletions: List<OnMarketCompletion<NoteMarketOutput>> = listOf(),
): WriteRequest<NoteMarketKey, NoteMarketInput, NoteMarketOutput> = WriteRequest.of(
    key = key,
    input = input,
    created = created,
    onCompletions = onCompletions,
)
