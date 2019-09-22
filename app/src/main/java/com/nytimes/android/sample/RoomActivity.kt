package com.nytimes.android.sample

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.nytimes.android.external.store3.pipeline.PipelineStore
import com.nytimes.android.external.store3.pipeline.ResponseOrigin
import com.nytimes.android.external.store3.pipeline.StoreRequest
import com.nytimes.android.external.store3.pipeline.StoreResponse
import com.nytimes.android.sample.reddit.PostAdapter
import kotlinx.android.synthetic.main.activity_room_store.*
import kotlinx.android.synthetic.main.activity_store.postRecyclerView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

@FlowPreview
class RoomActivity : AppCompatActivity() {
    @ExperimentalCoroutinesApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_room_store)
        setSupportActionBar(findViewById<View>(R.id.toolbar) as Toolbar)
        initUI()
    }

    @ObsoleteCoroutinesApi
    @ExperimentalCoroutinesApi
    private fun initUI() {
        val adapter = PostAdapter()
        // lazily set the adapter when we have data the first time so that RecyclerView can
        // restore position
        fun setAdapterIfNotSet() {
            if (postRecyclerView.adapter == null) {
                postRecyclerView.adapter = adapter
            }
        }
        val storeState = StoreState((application as SampleApp).roomPipeline)
        lifecycleScope.launchWhenStarted {
            fun refresh() {
                launch {
                    storeState.setKey(subredditInput.text.toString())
                }
            }

            pullToRefresh.setOnRefreshListener {
                refresh()
            }
            launch {
                storeState.loading.collect {
                    pullToRefresh.isRefreshing = it
                }
            }
            launch {
                storeState.errors.collect {
                    if (it != "") {
                        Snackbar.make(root, it, Snackbar.LENGTH_INDEFINITE).setAction(
                            "refresh"
                        ) {
                            refresh()
                        }.show()
                    }
                }
            }
            if (subredditInput.text.toString().trim() == "") {
                subredditInput.setText("aww")
            }
            fetchButton.setOnClickListener {
                refresh()
            }
            refresh()
            storeState.data.collect {
                setAdapterIfNotSet()
                adapter.submitList(it)
            }
        }
    }
}

/**
 * This class should possibly be moved to a helper library but needs more API work before that.
 */
internal class StoreState<Key, Output>(
    private val store : PipelineStore<Key, Output>
) {
    private val keyFlow = Channel<Key>(capacity = Channel.CONFLATED)
    private val _errors = Channel<String>(capacity = Channel.CONFLATED)
    val errors
        get() = _errors.consumeAsFlow()
    private val _loading = Channel<Boolean>(capacity = Channel.CONFLATED)
    val loading
        get() = _loading.consumeAsFlow()

    suspend fun setKey(key : Key) {
        _errors.send("")
        _loading.send(true)
        keyFlow.send(key)
    }

    val data = keyFlow.consumeAsFlow().flatMapLatest { key ->
        store.stream(
            StoreRequest.cached(
                key = key,
                refresh = true
            )
        ).onEach {
            if (it.origin == ResponseOrigin.Fetcher) {
                _loading.send(
                    it is StoreResponse.Loading
                )
            }
            if (it is StoreResponse.Error) {
                _errors.send(it.error.localizedMessage)
            }
        }.transform {
            if (it is StoreResponse.Data) {
                emit(it.value)
            }
        }
    }
}
