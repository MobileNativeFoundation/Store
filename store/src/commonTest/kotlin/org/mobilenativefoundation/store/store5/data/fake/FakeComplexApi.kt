package org.mobilenativefoundation.store.store5.data.fake

import org.mobilenativefoundation.store.store5.data.Api
import org.mobilenativefoundation.store.store5.data.model.NoteMarketKey
import org.mobilenativefoundation.store.store5.data.model.NoteMarketOutput

internal class FakeComplexApi : Api<NoteMarketKey, NoteMarketOutput> {
    override val data = mutableMapOf<NoteMarketKey, NoteMarketOutput>()

    init {
        reset()
    }

    override fun get(key: NoteMarketKey, fail: Boolean): NoteMarketOutput? {
        if (fail) {
            throw Exception()
        }

        return data[key]
    }

    override fun post(key: NoteMarketKey, value: NoteMarketOutput, fail: Boolean): NoteMarketOutput? {
        if (fail) {
            throw Exception()
        }

        return apply { data[key] = value }.data[key]
    }

    fun reset() {
        with(FakeComplexNotes.GetById) {
            data[One.key] = One.note
            data[Two.key] = Two.note
            data[Three.key] = Three.note
            data[Four.key] = Four.note
            data[Five.key] = Five.note
            data[Six.key] = Six.note
            data[Seven.key] = Seven.note
            data[Eight.key] = Eight.note
            data[Nine.key] = Nine.note
            data[Ten.key] = Ten.note
            data[Eleven.key] = Eleven.note
            data[Twelve.key] = Twelve.note
        }

        with(FakeComplexNotes.Paginate) {
            data[First.key] = First.note
            data[Second.key] = Second.note
            data[Third.key] = Third.note
            data[Fourth.key] = Fourth.note
        }
    }
}
