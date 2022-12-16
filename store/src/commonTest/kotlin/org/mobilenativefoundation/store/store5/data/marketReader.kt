package org.mobilenativefoundation.store.store5.data

import org.mobilenativefoundation.store.store5.ItemValidator
import org.mobilenativefoundation.store.store5.OnMarketCompletion
import org.mobilenativefoundation.store.store5.ReadRequest
import org.mobilenativefoundation.store.store5.data.model.Note
import org.mobilenativefoundation.store.store5.data.model.NoteCommonRepresentation
import org.mobilenativefoundation.store.store5.data.model.NoteMarketKey

internal fun readRequest(
    key: String,
    refresh: Boolean = false,
    onCompletions: List<OnMarketCompletion<Note>> = listOf(),
): ReadRequest<String, Note> = ReadRequest.of(
    key = key,
    onCompletions = onCompletions,
    refresh = refresh
)

internal fun readRequestWithValidator(
    key: String,
    isValid: Boolean = true,
    refresh: Boolean = false,
    onCompletions: List<OnMarketCompletion<Note>> = listOf()
): ReadRequest<String, Note> = ReadRequest.of(
    key = key,
    validator = ItemValidator.by { isValid },
    refresh = refresh,
    onCompletions = onCompletions
)

internal fun complexReadRequest(
    key: NoteMarketKey,
    refresh: Boolean = false,
    onCompletions: List<OnMarketCompletion<NoteCommonRepresentation>> = listOf()
): ReadRequest<NoteMarketKey, NoteCommonRepresentation> = ReadRequest.of(
    key = key,
    onCompletions = onCompletions,
    refresh = refresh
)
