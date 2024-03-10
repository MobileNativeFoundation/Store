package org.mobilenativefoundation.store.paging5.util

interface PostApi {
    suspend fun get(key: PostKey.Single): PostGetRequestResult

    suspend fun get(key: PostKey.Cursor): FeedGetRequestResult

    suspend fun put(post: PostData.Post): PostPutRequestResult
}
