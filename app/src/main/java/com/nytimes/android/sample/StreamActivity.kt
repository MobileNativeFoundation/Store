package com.nytimes.android.sample

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nytimes.android.external.store4.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class StreamActivity : AppCompatActivity(), CoroutineScope {

    override val coroutineContext: CoroutineContext = Job() + Dispatchers.Main

    @InternalCoroutinesApi
    @FlowPreview
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.stream_activity)

        var counter = 0

        val store = StoreBuilder
            .fromNonFlow { key:Int -> (key * 1000 + counter++).also { delay(1_000) } }
            .cachePolicy(
                MemoryPolicy
                    .builder()
                    .setExpireAfterWrite(10)
                    .setExpireAfterTimeUnit(TimeUnit.SECONDS)
                    .build()
            )
            .build()

        findViewById<View>(R.id.get_1).onClick {
            store.get(1)
        }

        findViewById<View>(R.id.fresh_1).onClick {
            store.fresh(1)
        }

        findViewById<View>(R.id.get_2).onClick {
            store.get(2)
        }

        findViewById<View>(R.id.fresh_2).onClick {
            store.fresh(2)
        }

        launch {
            store.stream(StoreRequest.cached(1, refresh = false)).collect {
                findViewById<TextView>(R.id.stream_1).text = "Stream 1 $it"
            }
        }
        launch {
            store.stream(StoreRequest.cached(2, refresh = false)).collect {
                findViewById<TextView>(R.id.stream_2).text = "Stream 2 $it"
            }
        }
        launch {
            flowOf(store.stream(StoreRequest.cached(1, refresh = false)),
                store.stream(StoreRequest.cached(2, refresh = false)))
                .flattenMerge()
                .collect {
                    findViewById<TextView>(R.id.stream).text = "Stream $it"
                }
        }
    }

    fun View.onClick(f: suspend () -> Unit) {
        setOnClickListener {
            launch {
                f()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineContext.cancel()
    }
}
