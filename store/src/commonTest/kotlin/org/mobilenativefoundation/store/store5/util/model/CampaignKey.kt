@file:Suppress("PropertyName")

package org.mobilenativefoundation.store.store5.util.model

import org.mobilenativefoundation.store.store5.StatefulStoreKey


open class CampaignKey(
        val key: String
) : StatefulStoreKey {
    override fun unprocessed() = Unprocessed(key)

    override fun processed() = Processed(key)
    data class Processed(val _key: String) : StatefulStoreKey.Processed, CampaignKey(_key) {
        override fun unprocessed() = Unprocessed(key)
        override fun processed() = this
    }

    data class Unprocessed(
            val _key: String
    ) : StatefulStoreKey.Unprocessed {
        override fun processed() = Processed(_key)
    }

}