package org.mobilenativefoundation.store.store5.util.fake

import kotlinx.coroutines.delay
import org.mobilenativefoundation.store.store5.util.model.Campaign
import org.mobilenativefoundation.store.store5.util.model.CampaignVariable

class CampaignProcessor {
    internal var counter = 0

    init {
        counter = 0
    }

    suspend fun processor(output: Campaign.Unprocessed, ttl: Long? = null): Campaign.Processed {
        counter += 1

        val words = output.text.split(SPACE)
        val processed = words.map { word ->
            when (CampaignVariable.lookup(word)) {
                CampaignVariable.Price -> fetchPrice()
                CampaignVariable.Plan -> fetchPlan()
                null -> word
            }
        }.joinToString(SPACE)

        return Campaign.Processed(
            id = output.id,
            text = processed,
            ttl = ttl
        )
    }

    private suspend fun fetchPrice(): String {
        delay(300)
        return "$11.99"
    }

    private suspend fun fetchPlan(): String {
        delay(300)
        return "Plus"
    }
}

private const val SPACE = " "
