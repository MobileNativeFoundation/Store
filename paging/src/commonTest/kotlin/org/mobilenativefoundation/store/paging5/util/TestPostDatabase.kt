package org.mobilenativefoundation.store.paging5.util

class TestPostDatabase(private val userId: String) : PostDatabase {
    private val posts = mutableMapOf<String, PostData.Post>()
    private val feeds = mutableMapOf<PostKey.Cursor, PostData.Feed>()

    override fun add(post: PostData.Post) {
        posts[post.id] = post

        val (key, feed) =
            feeds.entries.first { (_, feed) ->
                feed.posts.firstOrNull { it.postId == post.id } != null
            }

        val updatedPosts = feed.posts.toMutableList()
        val indexOfPost = updatedPosts.indexOfFirst { it.id == post.id }
        updatedPosts[indexOfPost] = post
        feeds[key] = feed.copy(posts = updatedPosts)
    }

    override fun add(
        key: PostKey.Cursor,
        feed: PostData.Feed,
    ) {
        feeds[key] = feed

        feed.posts.forEach { add(it) }
    }

    override fun findPostByPostId(postId: String): PostData.Post? {
        return posts[postId]
    }

    override fun findFeedByKey(
        key: PostKey.Cursor,
        size: Int,
    ): PostData.Feed? {
        return feeds[key]
    }
}
