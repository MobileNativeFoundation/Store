package org.mobilenativefoundation.store.paging5.util

interface PostDatabase {
    fun add(post: PostData.Post)
    fun add(key: PostKey.Cursor, feed: PostData.Feed)
    fun findPostByPostId(postId: String): PostData.Post?
    fun findFeedByKey(key: PostKey.Cursor, size: Int): PostData.Feed?
}
