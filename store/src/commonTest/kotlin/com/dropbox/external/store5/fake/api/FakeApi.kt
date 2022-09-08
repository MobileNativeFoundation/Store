package com.dropbox.external.store5.fake.api

import com.dropbox.external.store5.fake.FakeNotes
import com.dropbox.external.store5.fake.model.Note

internal class FakeApi : Api<String, Note> {
    override val data = mutableMapOf<String, Note>()

    init {
        data[FakeNotes.One.key] = FakeNotes.One.note
        data[FakeNotes.Two.key] = FakeNotes.Two.note
    }

    override fun get(key: String, fail: Boolean): Note? {
        if (fail) {
            throw Exception()
        }

        return data[key]
    }

    override fun post(key: String, value: Note, fail: Boolean): Note? {
        if (fail) {
            throw Exception()
        }

        return apply { data[key] = value }.data[key]
    }
}