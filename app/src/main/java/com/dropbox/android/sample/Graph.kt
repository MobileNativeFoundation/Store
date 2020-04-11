package com.dropbox.android.sample

import android.content.Context
import android.text.Html
import androidx.room.Room
import com.dropbox.android.sample.data.model.Children
import com.dropbox.android.sample.data.model.Post
import com.dropbox.android.sample.data.model.RedditDb
import com.dropbox.android.sample.data.remote.Api
import com.dropbox.android.external.fs3.FileSystemPersister
import com.dropbox.android.external.fs3.PathResolver
import com.dropbox.android.external.fs3.SourcePersisterFactory
import com.dropbox.android.external.fs3.filesystem.FileSystemFactory
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.MemoryPolicy
import com.dropbox.android.external.store4.Persister
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.legacy.BarCode
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.BufferedSource
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.IOException
import kotlin.time.seconds

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class, ExperimentalStdlibApi::class)
object Graph {
    private val moshi = Moshi.Builder().build()

    fun provideRoomStore(context: SampleApp): Store<String, List<Post>> {
        val db = provideRoom(context)
        return StoreBuilder
            .fromNonFlow { key: String ->
                provideRetrofit().fetchSubreddit(key, 10).data.children.map(::toPosts)
            }
            .persister(
                reader = db.postDao()::loadPosts,
                writer = db.postDao()::insertPosts,
                delete = db.postDao()::clearFeedBySubredditName,
                deleteAll = db.postDao()::clearAllFeeds
            )
            .build()
    }

    fun provideRoomStoreMultiParam(context: SampleApp): Store<Pair<String, RedditConfig>, List<Post>> {
        val db = provideRoom(context)
        return StoreBuilder
            .fromNonFlow<Pair<String, RedditConfig>, List<Post>> { (query, config) ->
                provideRetrofit().fetchSubreddit(query, config.limit)
                    .data.children.map(::toPosts)
            }
            .persister(reader = { (query, _) -> db.postDao().loadPosts(query) },
                writer = { (query, _), posts -> db.postDao().insertPosts(query, posts) },
                delete = { (query, _) -> db.postDao().clearFeedBySubredditName(query) },
                deleteAll = db.postDao()::clearAllFeeds
            )
            .build()
    }

    private fun provideRoom(context: SampleApp): RedditDb {
        return Room.inMemoryDatabaseBuilder(context, RedditDb::class.java)
            .build()
    }

    /**
     * Returns a new Persister with the cache as the root.
     */
    @Throws(IOException::class)
    fun newPersister(cacheDir: File): Persister<BufferedSource, BarCode> {
        return SourcePersisterFactory.create(cacheDir)
    }

    fun provideConfigStore(context: Context): Store<Unit, RedditConfig> {
        val fileSystem = FileSystemFactory.create(context.cacheDir)
        val fileSystemPersister =
            FileSystemPersister.create(fileSystem, object : PathResolver<Unit> {
                override fun resolve(key: Unit) = "config.json"
            })
        val adapter = moshi.adapter<RedditConfig>(RedditConfig::class.java)
        return StoreBuilder
            .fromNonFlow<Unit, RedditConfig> {
                delay(500)
                RedditConfig(10)
            }
            .nonFlowingPersister(
                reader = {
                    runCatching {
                        val source = fileSystemPersister.read(Unit)
                        source?.let { adapter.fromJson(it) }
                    }.getOrNull()
                },
                writer = { _, config ->
                    val buffer = Buffer()
                    withContext(Dispatchers.IO) {
                        adapter.toJson(buffer, config)
                    }
                    fileSystemPersister.write(Unit, buffer)
                }
            )
            .cachePolicy(
                MemoryPolicy.builder().setExpireAfterWrite(10.seconds).build()
            )
            .build()
    }

    private fun provideRetrofit(): Api {
        return Retrofit.Builder()
            .baseUrl("https://reddit.com/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(Api::class.java)
    }

    private fun toPosts(it: Children): Post {
        return it.data.copy(
            preview = it.data.preview?.let {
                it.copy(
                    images = it.images.map { image ->
                        @Suppress("DEPRECATION")
                        image.copy(
                            source = image.source.copy(
                                // preview urls are html encoded, need to escape
                                url = Html.fromHtml(image.source.url).toString()
                            )
                        )
                    }
                )
            }
        )
    }
}
