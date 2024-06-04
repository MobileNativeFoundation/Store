package org.mobilenativefoundation.paging.core.utils.timeline

import org.mobilenativefoundation.paging.core.PagingData
import org.mobilenativefoundation.paging.core.PagingKey


typealias Id = Int
typealias K = Int
typealias P = TimelineKeyParams
typealias CP = TimelineKeyParams.Collection
typealias SP = TimelineKeyParams.Single
typealias PK = PagingKey<Id, P>
typealias SK = PagingKey<Id, SP>
typealias CK = PagingKey<Id, CP>
typealias D = TimelineData
typealias PD = PagingData<Id, K, P, TimelineData>
typealias CD = PagingData.Collection<Int, Int, P, TimelineData>
typealias SD = PagingData.Single<Int, Int, P, TimelineData>
typealias A = TimelineAction
typealias E = TimelineError

sealed class TimelineError {
    data class Exception(val throwable: Throwable) : TimelineError()
}

sealed interface TimelineAction {
    data object ClearData : TimelineAction
}

sealed interface TimelineKeyParams {
    val headers: MutableMap<String, String>

    data class Single(
        override val headers: MutableMap<String, String> = mutableMapOf(),
    ) : TimelineKeyParams

    data class Collection(
        val size: Int,
        val filter: List<Filter<SD>> = emptyList(),
        val sort: Sort? = null,
        override val headers: MutableMap<String, String> = mutableMapOf()
    ) : TimelineKeyParams
}

sealed class TimelineData {
    data class Post(
        val id: Id,
        val content: String
    ) : TimelineData()

    data class Feed(
        val posts: List<Post>,
        val itemsBefore: Int,
        val itemsAfter: Int,
        val nextKey: PK?
    ) : TimelineData()
}

enum class KeyType {
    SINGLE,
    COLLECTION
}


/**
 * An enum defining sorting options that can be applied during fetching.
 */
enum class Sort {
    NEWEST,
    OLDEST,
    ALPHABETICAL,
    REVERSE_ALPHABETICAL,
}

/**
 * Defines filters that can be applied during fetching.
 */
interface Filter<T : Any> {
    operator fun invoke(items: List<T>): List<T>
}

