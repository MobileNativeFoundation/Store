package org.mobilenativefoundation.paging.core

/**
 * Represents a key used for paging along with its associated parameters.
 *
 * @param K The type of the key.
 * @param P The type of the parameters.
 * @property key The key value used for paging.
 * @property params The parameters associated with the key.
 */
data class PagingKey<out K : Any, out P : Any>(
    val key: K,
    val params: P,
)