package com.dropbox.store.campaigns.android.common.repository

import com.dropbox.store.campaigns.android.common.entity.Campaign

interface CampaignsRepository {
    suspend fun getBestCampaign(origin: String): Campaign
}