package org.mobilenativefoundation.store.store5.util.model

enum class CampaignVariable(val value: String) {
    Price("\${PRICE}"),
    Plan("\${PLAN}");

    companion object {
        private val values = values().associateBy { it.value }
        fun lookup(value: String): CampaignVariable? = values[value]
    }
}