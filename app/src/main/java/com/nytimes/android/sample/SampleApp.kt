package com.nytimes.android.sample

import android.app.Application
import com.nytimes.android.external.store3.base.Persister
import com.nytimes.android.external.store4.FlowStore
import com.nytimes.android.external.store4.legacy.BarCode
import com.nytimes.android.sample.Graph.newPersister
import com.nytimes.android.sample.Graph.provideRoomPipeline
import com.nytimes.android.sample.data.model.Post
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import okio.BufferedSource
import java.io.IOException

@FlowPreview
@ExperimentalCoroutinesApi
class SampleApp : Application() {
    lateinit var roomFlowStore: FlowStore<String, List<Post>>


    lateinit var persister: Persister<BufferedSource, BarCode>

    @FlowPreview
    @ExperimentalCoroutinesApi
    override fun onCreate() {
        super.onCreate()
        initPersister();
        roomFlowStore = provideRoomPipeline(this)
    }

    private fun initPersister() {
        try {
            persister = newPersister(this.cacheDir)
        } catch (exception: IOException) {
            throw RuntimeException(exception)
        }
    }






}
