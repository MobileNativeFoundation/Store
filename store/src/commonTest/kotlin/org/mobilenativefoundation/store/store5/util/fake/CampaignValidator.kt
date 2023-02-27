package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.Validator
import org.mobilenativefoundation.store.store5.impl.extensions.now
import org.mobilenativefoundation.store.store5.util.model.Campaign

internal class CampaignValidator(private val expiration: Long = now()) : Validator<Campaign> {
    override suspend fun isValid(item: Campaign): Boolean = when (item.ttl) {
        null -> true
        else -> item.ttl!! > expiration
    }
}
