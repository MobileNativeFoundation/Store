package org.mobilenativefoundation.paging.core.utils.timeline

interface FeedService {
    suspend fun get(key: CK): TimelineData.Feed
}