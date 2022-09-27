package com.dropbox.store.campaigns.android.common.repository

import com.dropbox.store.campaigns.android.common.entity.Note

interface NotesRepository {
    suspend fun getNotes(): List<Note>
}