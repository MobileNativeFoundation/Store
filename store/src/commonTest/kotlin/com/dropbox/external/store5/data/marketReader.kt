package com.dropbox.external.store5.data

import com.dropbox.external.store5.ItemValidator
import com.dropbox.external.store5.ReadRequest
import com.dropbox.external.store5.OnMarketCompletion
import com.dropbox.external.store5.data.model.Note

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
