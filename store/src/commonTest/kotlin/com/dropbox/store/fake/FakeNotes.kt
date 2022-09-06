package com.dropbox.store.fake

import com.dropbox.store.fake.model.Note
import com.dropbox.store.fake.model.NoteEntry

internal object FakeNotes {
    val One = NoteEntry("KEY-1", Note("1", "TITLE-1", "CONTENT-1"))
    val Two = NoteEntry("KEY-2", Note("2", "TITLE-2", "CONTENT-2"))
    val Three = NoteEntry("KEY-3", Note("3", "TITLE-3", "CONTENT-3"))
    val Four = NoteEntry("KEY-4", Note("4", "TITLE-4", "CONTENT-4"))
    val Five = NoteEntry("KEY-5", Note("5", "TITLE-5", "CONTENT-5"))
    val Six = NoteEntry("KEY-6", Note("6", "TITLE-6", "CONTENT-6"))
    val Seven = NoteEntry("KEY-7", Note("7", "TITLE-7", "CONTENT-7"))
    val Eight = NoteEntry("KEY-8", Note("8", "TITLE-8", "CONTENT-8"))
    val Nine = NoteEntry("KEY-9", Note("9", "TITLE-9", "CONTENT-9"))
    val Ten = NoteEntry("KEY-10", Note("10", "TITLE-10", "CONTENT-10"))
    val Eleven = NoteEntry("KEY-11", Note("11", "TITLE-11", "CONTENT-11"))

    fun list() = listOf(One, Two, Three, Four, Five, Six, Seven, Eight, Nine, Ten, Eleven)
    fun listN(n: Int) = list().subList(0, n)
}