package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.util.model.Campaign
import org.mobilenativefoundation.store.store5.util.model.CampaignKey

internal class CampaignApi {
    internal val db = mutableMapOf<String, Campaign.Unprocessed>()
    internal var counter = 0

    init {
        seed()
    }

    fun get(key: CampaignKey, fail: Boolean, ttl: Long?): Campaign.Unprocessed {
        counter += 1
        if (fail) {
            throw Exception()
        }

        val campaign = db[key.key]!!

        return if (ttl != null) {
            campaign.copy(ttl = ttl)
        } else {
            campaign
        }
    }

    private fun seed() {
        db["1"] = Campaign.Unprocessed("1", "Offline Files: \${OFFLINE_FILES_STATUS}, \${OFFLINE_FILE_COUNT}. Learn more at: \${OFFLINE_FILES_LINK}")
        counter = 0
    }
}
