package com.nytimes.android.sample.data.model

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow

/**
 * Keeps the association between a Post and a feed
 */
@Entity(
    primaryKeys = ["subredditName", "postOrder", "postId"],
    indices = [Index("postId", unique = false)]
)
data class FeedEntity(
    val subredditName: String,
    val postOrder: Int,
    val postId: String
)

// this wrapper usually doesn't make sense but we do here to avoid leaking room into the Model file
@Entity(
    primaryKeys = ["id"]
)
data class PostEntity(
    @Embedded
    val post: Post
)

class RedditTypeConverters {
    private val moshi = Moshi.Builder().build()
    private val previewAdapter = moshi.adapter<Preview>(Preview::class.java)

    @TypeConverter
    fun previewToString(preview: Preview?) = preview?.let {
        previewAdapter.toJson(it)
    }

    @TypeConverter
    fun stringToPreview(preview: String?) = preview?.let {
        previewAdapter.fromJson(it)
    }
}

@Dao
abstract class PostDao {
    @Transaction
    open suspend fun insertPosts(subredditName: String, posts: List<Post>) {
        // first clear the feed
        clearFeed(subredditName)
        // convert them into database models
        val feedEntities = posts.mapIndexed { index: Int, post: Post ->
            FeedEntity(
                subredditName = subredditName,
                postOrder = index,
                postId = post.id
            )
        }
        val postEntities = posts.map {
            PostEntity(it)
        }
        // save them into the database
        insertPosts(feedEntities, postEntities)
        // delete posts that are not part of any feed
        clearObseletePosts()
    }

    @Query("DELETE FROM FeedEntity WHERE subredditName = :subredditName")
    abstract suspend fun clearFeed(subredditName: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertPosts(
        feedEntries: List<FeedEntity>,
        posts: List<PostEntity>
    )

    @Query("DELETE FROM PostEntity WHERE id NOT IN (SELECT DISTINCT(postId) FROM FeedEntity)")
    protected abstract suspend fun clearObseletePosts()

    @Query(
        """
        SELECT PostEntity.* FROM FeedEntity
            LEFT JOIN PostEntity ON FeedEntity.postId = PostEntity.id
            WHERE subredditName = :subredditName
            ORDER BY FeedEntity.postOrder ASC
        """
    )
    abstract fun loadPosts(subredditName: String): Flow<List<Post>>
}

@Database(
    version = 1,
    exportSchema = false,
    entities = [PostEntity::class, FeedEntity::class]
)
@TypeConverters(RedditTypeConverters::class)
abstract class RedditDb : RoomDatabase() {
    abstract fun postDao(): PostDao
}
