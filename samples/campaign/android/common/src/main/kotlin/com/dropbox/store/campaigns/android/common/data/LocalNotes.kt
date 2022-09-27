package com.dropbox.store.campaigns.android.common.data

import com.dropbox.store.campaigns.android.common.entity.Note

object LocalNotes {
    private val One = Note("1", "Title-1", "Content-1")
    private val Two = Note("2", "Title-2", "Content-2")
    private val Three = Note("3", "Title-3", "Content-3")
    private val Four = Note("4", "Title-4", "Content-4")
    private val Five = Note("5", "Title-5", "Content-5")

    fun list(): List<Note> = listOf(One, Two, Three, Four, Five)
    fun list(n: Int): List<Note> = list().subList(0, n)
}