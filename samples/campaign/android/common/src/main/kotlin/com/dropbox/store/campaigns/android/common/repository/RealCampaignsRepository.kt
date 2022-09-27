package com.dropbox.store.campaigns.android.common.repository

import com.dropbox.store.campaigns.android.common.entity.Campaign
import com.dropbox.store.campaigns.android.common.scope.SingleIn
import com.dropbox.store.campaigns.android.common.scope.UserScope
import com.squareup.anvil.annotations.ContributesBinding
import javax.inject.Inject

@SingleIn(UserScope::class)
@ContributesBinding(UserScope::class)
class RealCampaignsRepository @Inject constructor() : CampaignsRepository {
    override suspend fun getBestCampaign(origin: String): Campaign {
        TODO("Not yet implemented")
    }

}