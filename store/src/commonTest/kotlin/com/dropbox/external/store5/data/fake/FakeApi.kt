package com.dropbox.external.store5.data.fake

import com.dropbox.external.store5.data.Api
import com.dropbox.external.store5.data.model.Note

internal class FakeApi : Api<String, Note> {
    override val data = mutableMapOf<String, Note>()

    init {
        reset()
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

    fun reset() {
        data[FakeNotes.One.key] = FakeNotes.One.note
        data[FakeNotes.Two.key] = FakeNotes.Two.note
        data[FakeNotes.Three.key] = FakeNotes.Three.note
        data[FakeNotes.Four.key] = FakeNotes.Four.note
        data[FakeNotes.Five.key] = FakeNotes.Five.note
        data[FakeNotes.Six.key] = FakeNotes.Six.note
        data[FakeNotes.Seven.key] = FakeNotes.Seven.note
        data[FakeNotes.Eight.key] = FakeNotes.Eight.note
        data[FakeNotes.Nine.key] = FakeNotes.Nine.note
        data[FakeNotes.Ten.key] = FakeNotes.Ten.note
        data[FakeNotes.Eleven.key] = FakeNotes.Eleven.note
    }
}
