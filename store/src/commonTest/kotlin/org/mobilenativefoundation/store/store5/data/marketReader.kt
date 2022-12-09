package org.mobilenativefoundation.store.store5.data

import org.mobilenativefoundation.store.store5.ItemValidator
import org.mobilenativefoundation.store.store5.OnMarketCompletion
import org.mobilenativefoundation.store.store5.ReadRequest
import org.mobilenativefoundation.store.store5.data.model.Note
import org.mobilenativefoundation.store.store5.data.model.NoteMarketInput
import org.mobilenativefoundation.store.store5.data.model.NoteMarketKey
import org.mobilenativefoundation.store.store5.data.model.NoteMarketOutput

internal fun readRequest(
    key: String,
    refresh: Boolean = false,
    onCompletions: List<OnMarketCompletion<Note>> = listOf(),
): ReadRequest<String, Note, Note> = ReadRequest.of(
    key = key,
    onCompletions = onCompletions,
    refresh = refresh
)

internal fun readRequestWithValidator(
    key: String,
    isValid: Boolean = true,
    refresh: Boolean = false,
    onCompletions: List<OnMarketCompletion<Note>> = listOf()
): ReadRequest<String, Note, Note> = ReadRequest.of(
    key = key,
    validator = ItemValidator.by { isValid },
    refresh = refresh,
    onCompletions = onCompletions
)

internal fun complexReadRequest(
    key: NoteMarketKey,
    refresh: Boolean = false,
    onCompletions: List<OnMarketCompletion<NoteMarketOutput>> = listOf()
): ReadRequest<NoteMarketKey, NoteMarketInput, NoteMarketOutput> = ReadRequest.of(
    key = key,
    onCompletions = onCompletions,
    refresh = refresh
)
