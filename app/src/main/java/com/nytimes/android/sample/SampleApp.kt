package com.nytimes.android.sample

import android.app.Application
import com.nytimes.android.external.store4.Persister
import com.nytimes.android.external.store4.Store
import com.nytimes.android.external.store4.legacy.BarCode
import com.nytimes.android.sample.data.model.Post
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
        roomStore = Graph.provideRoomPipeline(this)
    }

    private fun initPersister() {
        try {
            persister = Graph.newPersister(this.cacheDir)
        } catch (exception: IOException) {
            throw RuntimeException(exception)
        }
    }
}
