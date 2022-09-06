package com.dropbox.market.notes.android.data.api

import com.dropbox.market.notes.android.Note

internal object FakeNotes {
    val One = Note("1", "1", "TITLE-1", "CONTENT-1")
    val Two = Note("2", "2", "TITLE-2", "CONTENT-2")
    val Three = Note("3", "3", "TITLE-3", "CONTENT-3")
    val Four = Note("4", "4", "TITLE-4", "CONTENT-4")
    val Five = Note("5", "5", "TITLE-5", "CONTENT-5")
    val Six = Note("6", "6", "TITLE-6", "CONTENT-6")
    val Seven = Note("7", "7", "TITLE-7", "CONTENT-7")
    val Eight = Note("8", "8", "TITLE-8", "CONTENT-8")
    val Nine = Note("9", "9", "TITLE-9", "CONTENT-9")
    val Ten = Note("10", "10", "TITLE-10", "CONTENT-10")
    val Eleven = Note("11", "11", "TITLE-11", "CONTENT-11")

    fun list() = listOf(One, Two, Three, Four, Five, Six, Seven, Eight, Nine, Ten, Eleven)
    fun listN(n: Int) = list().subList(0, n)
}