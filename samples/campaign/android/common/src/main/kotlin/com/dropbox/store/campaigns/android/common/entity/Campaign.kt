package com.dropbox.store.campaigns.android.common.entity

import com.dropbox.store.campaigns.android.common.viewmodel.entity.Data
import kotlinx.serialization.Serializable

@Serializable
sealed class Campaign : Data {
    @Serializable
    data class Button(val text: String, val onConfirm: String) : Campaign()

    @Serializable
    data class Modal(val heading: String, val text: String, val onConfirm: String) : Campaign()

    @Serializable
    data class Banner(val heading: String, val icon: String, val onConfirm: String) : Campaign()

    @Serializable
    data class UpgradePage(val title: String, val image: String, val buttonText: String) : Campaign()
}