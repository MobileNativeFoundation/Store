package com.nytimes.android.sample

import android.text.Html
import androidx.room.Room
import com.jakewharton.retrofit2.adapter.kotlin.coroutines.CoroutineCallAdapterFactory
import com.nytimes.android.external.fs3.SourcePersisterFactory
import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store4.FlowStore
import com.nytimes.android.external.store4.RealFlowStore
import com.nytimes.android.external.store4.legacy.BarCode
import com.nytimes.android.sample.data.model.Children
import com.nytimes.android.sample.data.model.Post
import com.nytimes.android.sample.data.model.RedditDb
import com.nytimes.android.sample.data.remote.Api
import com.squareup.moshi.Moshi
import okio.BufferedSource
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.IOException

object Graph {
     fun provideRoomPipeline(context: SampleApp): FlowStore<String, List<Post>> {
        val db = provideRoom(context)
        return RealFlowStore
                .fromNonFlow<String, List<Post>, List<Post>> {
                    provideRetrofit().fetchSubreddit(it, "10")
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