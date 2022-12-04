package com.dropbox.external.store5.data

import com.dropbox.external.store5.WriteRequest
import com.dropbox.external.store5.OnMarketCompletion
import com.dropbox.external.store5.data.model.Note
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
