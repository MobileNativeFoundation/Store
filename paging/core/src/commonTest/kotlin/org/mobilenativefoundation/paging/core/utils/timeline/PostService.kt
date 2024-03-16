package org.mobilenativefoundation.paging.core.utils.timeline

interface PostService {
    suspend fun get(key: SK): TimelineData.Post?
    suspend fun update(key: SK, value: TimelineData.Post)
}
