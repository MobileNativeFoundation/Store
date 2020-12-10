package com.dropbox.android.sample

import android.app.Application
import com.dropbox.android.external.fs3.Persister
import com.dropbox.kmp.external.store4.Store
import com.dropbox.android.sample.data.model.Post
import okio.BufferedSource
import java.io.IOException

class SampleApp : Application() {
    lateinit var roomStore: Store<String, List<Post>>

    lateinit var storeMultiParam: Store<Pair<String, RedditConfig>, List<Post>>

    lateinit var configStore: Store<Unit, RedditConfig>

    lateinit var persister: Persister<BufferedSource, Pair<String, String>>

    override fun onCreate() {
        super.onCreate()
        initPersister()
        roomStore = Graph.provideRoomStore(this)
        storeMultiParam = Graph.provideRoomStoreMultiParam(this)
        configStore = Graph.provideConfigStore(this)
    }

    private fun initPersister() {
        try {
            persister = Graph.newPersister(this.cacheDir)
        } catch (exception: IOException) {
            throw RuntimeException(exception)
        }
    }
}
