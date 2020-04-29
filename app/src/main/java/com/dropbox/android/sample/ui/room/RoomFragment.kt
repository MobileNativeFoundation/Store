package com.dropbox.android.sample.ui.room

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.android.sample.R
import com.dropbox.android.sample.SampleApp
import com.dropbox.android.sample.reddit.PostAdapter
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_store.postRecyclerView
import kotlinx.android.synthetic.main.fragment_room_store.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

@FlowPreview
class RoomFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_room_store, container, false)
    }

    @ExperimentalCoroutinesApi
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initUI()
    }

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

        val storeState = StoreState((activity?.application as SampleApp).roomStore)
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
@FlowPreview
@ExperimentalCoroutinesApi
internal class StoreState<Key : Any, Output : Any>(
    private val store: Store<Key, Output>
) {
    private val keyFlow = Channel<Key>(capacity = Channel.CONFLATED)
    private val _errors = Channel<String>(capacity = Channel.CONFLATED)
    val errors
        get() = _errors.consumeAsFlow()
    private val _loading = Channel<Boolean>(capacity = Channel.CONFLATED)
    val loading
        get() = _loading.consumeAsFlow()

    suspend fun setKey(key: Key) {
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
            when (it) {
                is StoreResponse.Error.Exception -> _errors.send(it.error.localizedMessage!!)
                is StoreResponse.Error.Message -> _errors.send(it.message)
            }
        }.transform {
            if (it is StoreResponse.Data) {
                emit(it.value)
            }
        }
    }
}
