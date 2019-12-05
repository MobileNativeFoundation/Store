package com.dropbox.android.sample

import android.app.Application
import com.dropbox.android.external.store4.Persister
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.legacy.BarCode
import com.dropbox.android.sample.data.model.Post
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import okio.BufferedSource

@FlowPreview
@ExperimentalCoroutinesApi
class SampleApp : Application() {
    lateinit var roomStore: Store<String, List<Post>>

    lateinit var persister: Persister<BufferedSource, BarCode>

    @FlowPreview
    @ExperimentalCoroutinesApi
    override fun onCreate() {
        super.onCreate()
        initPersister()
        roomStore = Graph.provideRoomStore(this)
    }

    private fun initPersister() {
        try {
            persister = Graph.newPersister(this.cacheDir)
        } catch (exception: IOException) {
            throw RuntimeException(exception)
        }
    }
}
