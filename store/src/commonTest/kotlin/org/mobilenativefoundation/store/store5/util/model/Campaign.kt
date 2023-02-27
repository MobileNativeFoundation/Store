package org.mobilenativefoundation.store.store5.util.model

sealed class Campaign {

    abstract val id: String
    abstract val text: String
    abstract val ttl: Long?

    data class Unprocessed(
        override val id: String,
        override val text: String,
        override val ttl: Long? = null
    ) : Campaign()

    data class Processed(
        override val id: String,
        override val text: String,
        override val ttl: Long? = null
    ) : Campaign()
}
