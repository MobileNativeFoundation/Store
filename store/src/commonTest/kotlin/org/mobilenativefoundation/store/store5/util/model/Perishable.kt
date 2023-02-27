package org.mobilenativefoundation.store.store5.util.model

data class Perishable<T : Any>(
    val value: T,
    val ttl: Long = Long.MAX_VALUE
)
