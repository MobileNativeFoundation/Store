package com.dropbox.android.sample.data.remote

import com.dropbox.android.sample.data.model.RedditData
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface Api {

    @GET("r/{subredditName}/new/.json")
    suspend fun fetchSubreddit(
        @Path("subredditName") subredditName: String,
        @Query("limit") limit: String
    ): RedditData

    @GET("r/{subredditName}/new/.json")
    suspend fun fetchSubredditForPersister(
        @Path("subredditName") subredditName: String,
        @Query("limit") limit: String
    ): ResponseBody
}
