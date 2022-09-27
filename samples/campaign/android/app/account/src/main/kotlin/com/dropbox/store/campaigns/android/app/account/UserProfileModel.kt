package com.dropbox.store.campaigns.android.account

import android.provider.ContactsContract

sealed class UserProfileModel {
    object Initial : UserProfileModel()
    object Loading : UserProfileModel()
    data class Success(
        val user: String,
        val profile: ContactsContract.Profile,
    ) : UserProfileModel()

    object Failure : UserProfileModel()
}