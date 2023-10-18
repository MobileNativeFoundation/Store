package org.mobilenativefoundation.store.paging5.util

interface PostApi {
    suspend fun get(postId: String): PostGetRequestResult
    suspend fun get(cursor: String?, size: Int): FeedGetRequestResult
    suspend fun put(post: PostData.Post): PostPutRequestResult
}