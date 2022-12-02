package com.dropbox.external.store5.data

import com.dropbox.external.store5.ItemValidator
import com.dropbox.external.store5.MarketReader
import com.dropbox.external.store5.OnMarketCompletion
import com.dropbox.external.store5.data.model.Note

internal fun marketReader(
    key: String,
    refresh: Boolean = false,
    onCompletions: List<OnMarketCompletion<Note>> = listOf(),
): MarketReader<String, Note, Note> = MarketReader.by(
    key = key,
    onCompletions = onCompletions,
    refresh = refresh
)

internal fun marketReaderWithValidator(
    key: String,
    isValid: Boolean = true,
    refresh: Boolean = false,
    onCompletions: List<OnMarketCompletion<Note>> = listOf()
): MarketReader<String, Note, Note> = MarketReader.by(
    key = key,
    validator = ItemValidator.by { isValid },
    refresh = refresh,
    onCompletions = onCompletions
)
