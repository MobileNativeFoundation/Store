package com.dropbox.store.campaigns.android.common.entity

import com.dropbox.store.campaigns.android.common.viewmodel.entity.Data
import kotlinx.serialization.Serializable

@Serializable
data class Note(
    val id: String,
    val title: String,
    val content: String
): Data