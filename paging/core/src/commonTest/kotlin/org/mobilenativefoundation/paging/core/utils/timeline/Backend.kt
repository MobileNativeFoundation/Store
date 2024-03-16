package org.mobilenativefoundation.paging.core.utils.timeline

import kotlinx.coroutines.flow.MutableStateFlow
import org.mobilenativefoundation.paging.core.PagingKey
import kotlin.math.max

class Backend {

    private val posts = mutableMapOf<SK, TimelineData.Post>()
    private val error = MutableStateFlow<Throwable?>(null)
    private val tries: MutableMap<CK, Int> = mutableMapOf()
    private val logs = mutableListOf<Event>()

    private val headers: MutableMap<CK, MutableMap<String, String>> = mutableMapOf()

    init {
        (1..200).map { TimelineData.Post(it, "Post $it") }.forEach { this.posts[PagingKey(it.id, TimelineKeyParams.Single())] = it }
    }

    val feedService: FeedService = RealFeedService(posts.values.toList(), error, { key ->
        if (key !in tries) {
            tries[key] = 0
        }

        tries[key] = tries[key]!! + 1
    }, { key ->
        if (key !in headers) {
            headers[key] = key.params.headers
        }

        val mergedHeaders = headers[key]!! + key.params.headers

        headers[key] = mergedHeaders.toMutableMap()
    })

    val postService: PostService = RealPostService(posts, error)

    fun failWith(error: Throwable) {
        this.error.value = error
    }

    fun clearError() {
        this.error.value = null
    }

    fun getRetryCountFor(key: CK): Int {
        val tries = tries[key] ?: 0
        val retries = tries - 1
        return max(retries, 0)
    }

    fun getHeadersFor(key: CK): Map<String, String> {
        val headers = this.headers[key] ?: mapOf()
        return headers
    }

    fun log(name: String, message: String) {
        logs.add(Event(name, message))
    }

    fun getLogs() = logs
}