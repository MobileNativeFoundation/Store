package org.mobilenativefoundation.paging.core.utils.timeline

import kotlinx.coroutines.flow.StateFlow
import org.mobilenativefoundation.paging.core.PagingKey

class RealFeedService(
    private val posts: List<TimelineData.Post>,
    private val error: StateFlow<Throwable?>,
    private val incrementTriesFor: (key: CK) -> Unit,
    private val setHeaders: (key: CK) -> Unit
) : FeedService {

    override suspend fun get(key: CK): TimelineData.Feed {
        setHeaders(key)

        error.value?.let {
            incrementTriesFor(key)
            throw it
        }

        val start = key.key
        val end = start + key.params.size
        val posts = this.posts.subList(start, end)

        return TimelineData.Feed(
            posts,
            itemsBefore = start - 1,
            itemsAfter = this.posts.size - end,
            nextKey = PagingKey(end, key.params)
        )
    }
}