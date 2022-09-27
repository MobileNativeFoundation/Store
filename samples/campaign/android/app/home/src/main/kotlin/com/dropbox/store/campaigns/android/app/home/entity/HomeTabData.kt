package com.dropbox.store.campaigns.android.home.entity

import com.dropbox.store.campaigns.android.common.entity.Note
import com.dropbox.store.campaigns.android.common.entity.User
import com.dropbox.store.campaigns.android.common.viewmodel.entity.Data

data class HomeTabData(
    val title: String,
    val user: User,
    val notes: List<Note>
) : Data