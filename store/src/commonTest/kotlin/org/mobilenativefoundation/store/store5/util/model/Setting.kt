package org.mobilenativefoundation.store.store5.util.model

sealed class Setting {
    abstract val id: String
    abstract val title: String
    abstract val subtitle: String
    abstract val label: String
    abstract val status: String

    abstract val ttl: Long

    data class Unprocessed(
            override val id: String,
            override val title: String,
            override val subtitle: String,
            override val label: String,
            override val status: String,
            override val ttl: Long = Long.MAX_VALUE
    ) : Setting()

    data class Processed(
            override val id: String,
            override val title: String,
            override val subtitle: String,
            override val label: String,
            override val status: String,
            override val ttl: Long = Long.MAX_VALUE
    ) : Setting()
}