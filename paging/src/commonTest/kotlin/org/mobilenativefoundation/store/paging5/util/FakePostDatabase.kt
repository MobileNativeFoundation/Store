package org.mobilenativefoundation.store.paging5.util

class FakePostDatabase(private val userId: String) : PostDatabase {
    private val posts = mutableMapOf<String, PostData.Post>()
    private val feeds = mutableMapOf<String, PostData.Feed>()
    override fun add(post: PostData.Post) {
        posts[post.id] = post

        val nextFeed = feeds[userId]?.posts?.map {
            if (it.postId == post.postId) {
                post
            } else {
                it
            }
        }

        nextFeed?.let {
            feeds[userId] = PostData.Feed(nextFeed)
            println("UPDATED FEED $it")
        }
    }

    override fun add(feed: PostData.Feed) {
        feeds[userId] = feed
    }

    override fun findPostByPostId(postId: String): PostData.Post? {
        return posts[postId]
    }

    override fun findFeedByUserId(cursor: String?, size: Int): PostData.Feed? {
        val feed = feeds[userId]
        println("FEED RETURNING = $feed")
        return feed

    }

}