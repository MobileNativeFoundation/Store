package com.dropbox.store.campaigns.android.common.api

import com.dropbox.store.campaigns.android.common.entity.Campaign
import com.dropbox.store.campaigns.android.common.entity.Note
import com.dropbox.store.campaigns.android.common.entity.Notification
import com.dropbox.store.campaigns.android.common.entity.User

interface Api {
    suspend fun getBestCampaign(origin: String): Result<Campaign>
    suspend fun getNotes(): Result<List<Note>>
    suspend fun getNotifications(): Result<List<Notification>>
    suspend fun getUser(): Result<User>
}