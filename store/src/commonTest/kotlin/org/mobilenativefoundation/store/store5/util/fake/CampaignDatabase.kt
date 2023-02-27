package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.util.model.Campaign
import org.mobilenativefoundation.store.store5.util.model.CampaignKey

internal class CampaignDatabase {
    private val db: MutableMap<CampaignKey, Campaign?> = mutableMapOf()
    fun put(key: CampaignKey, input: Campaign, fail: Boolean = false): Boolean {
        if (fail) {
            throw Exception()
        }

        println("Db")

        db[key] = input
        return true
    }

    fun get(key: CampaignKey, fail: Boolean = false): Campaign? {
        if (fail) {
            throw Exception()
        }

        return db[key]
    }

    fun clear(key: CampaignKey, fail: Boolean = false): Boolean {
        if (fail) {
            throw Exception()
        }
        db.remove(key)
        return true
    }

    fun clear(fail: Boolean = false): Boolean {
        if (fail) {
            throw Exception()
        }
        db.clear()
        return true
    }
}
