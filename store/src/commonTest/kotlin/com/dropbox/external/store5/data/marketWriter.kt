package com.dropbox.external.store5.data

import com.dropbox.external.store5.MarketWriter
import com.dropbox.external.store5.OnMarketCompletion
import com.dropbox.external.store5.data.model.Note
import kotlinx.datetime.Clock

internal fun marketWriter(
    key: String,
    input: Note,
    created: Long = Clock.System.now().epochSeconds,
    onCompletions: List<OnMarketCompletion<Note>> = listOf(),
): MarketWriter<String, Note, Note> = MarketWriter.by(
    key = key,
    input = input,
    created = created,
    onCompletions = onCompletions,
)