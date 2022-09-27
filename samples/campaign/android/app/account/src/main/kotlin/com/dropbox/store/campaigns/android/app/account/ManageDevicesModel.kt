package com.dropbox.store.campaigns.android.account

sealed class ManageDevicesModel {
    object Initial : ManageDevicesModel()
    object Loading : ManageDevicesModel()
    data class Success(
        val linkedDevices: List<String>,
    ) : ManageDevicesModel()

    object Failure : ManageDevicesModel()
}