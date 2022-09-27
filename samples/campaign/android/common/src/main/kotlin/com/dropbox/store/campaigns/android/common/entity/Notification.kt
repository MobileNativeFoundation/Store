package com.dropbox.store.campaigns.android.common.entity

import com.dropbox.store.campaigns.android.common.viewmodel.entity.Data
import kotlinx.serialization.Serializable

@Serializable
data class Notification(
    val id: String,
    val type: String
): Data