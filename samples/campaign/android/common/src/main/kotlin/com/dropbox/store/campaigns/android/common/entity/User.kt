package com.dropbox.store.campaigns.android.common.entity

import com.dropbox.store.campaigns.android.common.viewmodel.entity.Data
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val name: String,
    val email: String,
    val image: String
) : Data