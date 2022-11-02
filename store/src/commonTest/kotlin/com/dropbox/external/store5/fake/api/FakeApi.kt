package com.dropbox.external.store5.fake.api

import com.dropbox.external.store5.fake.FakeNotes
import com.dropbox.external.store5.fake.model.Note

internal class FakeApi : Api<String, Note> {
    override val data = mutableMapOf<String, Note>()

    internal var numGetRequests = 0
    internal var numPostRequests = 0

    init {
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

    override fun get(key: String, fail: Boolean): Note? {
        numGetRequests += 1
        if (fail) {
            throw Exception()
        }

        return data[key]
    }

    override fun post(key: String, value: Note, fail: Boolean): Note? {
        numPostRequests += 1
        if (fail) {
            throw Exception()
        }

        return apply { data[key] = value }.data[key]
    }
}