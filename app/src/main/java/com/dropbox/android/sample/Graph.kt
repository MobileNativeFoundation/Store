package com.dropbox.android.sample

import android.text.Html
import androidx.room.Room
import com.jakewharton.retrofit2.adapter.kotlin.coroutines.CoroutineCallAdapterFactory
import com.dropbox.android.external.fs3.SourcePersisterFactory
import com.dropbox.android.external.store4.Persister
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.legacy.BarCode
import com.dropbox.android.sample.data.model.Children
import com.dropbox.android.sample.data.model.Post
import com.dropbox.android.sample.data.model.RedditDb
import com.dropbox.android.sample.data.remote.Api
import com.squareup.moshi.Moshi
import java.io.File
import java.io.IOException
import okio.BufferedSource
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

object Graph {
    fun provideRoomStore(context: SampleApp): Store<String, List<Post>> {
        val db = provideRoom(context)
        return StoreBuilder
                .fromNonFlow { key: String ->
                    provideRetrofit().fetchSubreddit(key, "10")
                            .await().data.children.map(::toPosts)
                }
                .persister(reader = db.postDao()::loadPosts,
                        writer = db.postDao()::insertPosts,
                        delete = db.postDao()::clearFeed)
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

    private fun provideRetrofit(): Api {
        return Retrofit.Builder()
                .baseUrl("https://reddit.com/")
                .addConverterFactory(MoshiConverterFactory.create(provideMoshi()))
                .addCallAdapterFactory(CoroutineCallAdapterFactory())
                .build()
                .create(Api::class.java)
    }

    private fun toPosts(it: Children): Post {
        return it.data.copy(
                preview = it.data.preview?.let {
                    it.copy(
                            images = it.images.map {
                                @Suppress("DEPRECATION")
                                it.copy(
                                        source = it.source.copy(
                                                // preview urls are html encoded, need to escape
                                                url = Html.fromHtml(it.source.url).toString()
                                        )
                                )
                            }
                    )
                }
        )
    }

    fun provideMoshi() = Moshi.Builder().build()
}
