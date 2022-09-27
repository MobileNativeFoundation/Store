package com.dropbox.store.campaigns.android.account

sealed class ManageSpaceModel {
    object Initial : ManageSpaceModel()
    object Loading : ManageSpaceModel()
    data class Success(
        val megabytesAllowed: Long,
        val megabytesTotal: Long
    ) : ManageSpaceModel()

    object Failure : ManageSpaceModel()
}