package org.mobilenativefoundation.paging.core.utils.timeline

import kotlinx.coroutines.flow.StateFlow

class RealPostService(
    private val posts: MutableMap<SK, TimelineData.Post>,
    private val error: StateFlow<Throwable?>
) : PostService {
    override suspend fun get(key: SK): TimelineData.Post? {
        error.value?.let { throw it }

        return posts[key]
    }

    override suspend fun update(key: SK, value: TimelineData.Post) {
        error.value?.let { throw it }

        posts[key] = value
    }

}