package com.dropbox.store.campaigns.android.common.entity

import com.dropbox.store.campaigns.android.common.viewmodel.entity.Data
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val bio: String,
    val location: String,
): Data