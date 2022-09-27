package com.dropbox.store.campaigns.android.common.api

import com.dropbox.store.campaigns.android.common.data.LocalCampaigns
import com.dropbox.store.campaigns.android.common.data.LocalNotes
import com.dropbox.store.campaigns.android.common.data.LocalNotifications
import com.dropbox.store.campaigns.android.common.data.LocalUsers
import com.dropbox.store.campaigns.android.common.entity.Campaign
import com.dropbox.store.campaigns.android.common.entity.Note
import com.dropbox.store.campaigns.android.common.entity.Notification
import com.dropbox.store.campaigns.android.common.entity.User
import javax.inject.Inject

class HardcodedApi @Inject constructor() : Api {
    override suspend fun getBestCampaign(origin: String): Result<Campaign> = when (origin) {
        "HOME" -> Result.Success(LocalCampaigns.LaunchPurchaseFlowBanner)
        "ACCOUNT" -> Result.Success(LocalCampaigns.LaunchPurchaseFlowButton)
        else -> Result.Failure(Exception())
    }

    override suspend fun getNotes(): Result<List<Note>> = Result.Success(LocalNotes.list())
    override suspend fun getNotifications(): Result<List<Notification>> = Result.Success(LocalNotifications.list())
    override suspend fun getUser(): Result<User> = Result.Success(LocalUsers.Tag)
}