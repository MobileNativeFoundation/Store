package org.mobilenativefoundation.store.paging5.util

interface PostDatabase {
    fun add(post: PostData.Post)
    fun add(feed: PostData.Feed)
    fun findPostByPostId(postId: String): PostData.Post?
    fun findFeedByUserId(cursor: String?, size: Int): PostData.Feed?
}