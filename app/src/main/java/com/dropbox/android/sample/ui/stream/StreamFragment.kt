package com.dropbox.android.sample.ui.stream

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.dropbox.android.external.store4.*
import com.dropbox.android.sample.R
import kotlinx.android.synthetic.main.fragment_stream.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flowOf
import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalCoroutinesApi
class StreamFragment : Fragment(), CoroutineScope {

    override val coroutineContext: CoroutineContext = Job() + Dispatchers.Main

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_stream, container, false)
    }

    @ExperimentalTime
    @InternalCoroutinesApi
    @FlowPreview
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        var counter = 0

        val store = StoreBuilder
            .from(
                Fetcher.of { key: Int ->
                    (key * 1000 + counter++).also { delay(1_000) }
                }
            )
            .cachePolicy(
                MemoryPolicy
                    .builder<Any, Any>()
                    .setExpireAfterWrite(10.seconds)
                    .build()
            )
            .build()

        get_1.onClick {
            store.get(1)
        }

        fresh_1.onClick {
            store.fresh(1)
        }

        get_2.onClick {
            store.get(2)
        }

        fresh_2.onClick {
            store.fresh(2)
        }

        launch {
            store.stream(StoreRequest.cached(1, refresh = false)).collect {
                stream_1.text = "Stream 1 $it"
            }
        }
        launch {
            store.stream(StoreRequest.cached(2, refresh = false)).collect {
                stream_2.text = "Stream 2 $it"
            }
        }
        launch {
            flowOf(
                store.stream(StoreRequest.cached(1, refresh = false)),
                store.stream(StoreRequest.cached(2, refresh = false))
            )
                .flattenMerge()
                .collect {
                    stream.text = "Stream $it"
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
