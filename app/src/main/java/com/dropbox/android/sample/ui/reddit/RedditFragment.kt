package com.dropbox.android.sample.ui.reddit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.dropbox.android.sample.R
import com.dropbox.android.sample.data.model.Post
import com.dropbox.android.sample.reddit.PostAdapter
import com.dropbox.android.sample.utils.Lce
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.fragment_room_store.*
import kotlinx.android.synthetic.main.activity_store.postRecyclerView

class RedditFragment : Fragment() {

    private val viewModel: RedditViewModel by viewModels()

    private val adapter = PostAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_room_store, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        viewModel.liveData.observe(
            viewLifecycleOwner,
            Observer { lce: Lce<List<Post>> ->
                pullToRefresh.isRefreshing = lce is Lce.Loading
                when (lce) {
                    is Lce.Error -> showErrorMessage(lce.message)
                    is Lce.Success -> updateData(lce.data)
                }
            }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pullToRefresh.setOnRefreshListener {
            viewModel.refresh(subredditInput.text.toString())
        }

        fetchButton.setOnClickListener {
            viewModel.refresh(subredditInput.text.toString())
        }
    }

    private fun updateData(data: List<Post>) {
        // lazily set the adapter when we have data the first time so that RecyclerView can
        // restore position
        if (postRecyclerView.adapter == null) {
            postRecyclerView.adapter = adapter
        }
        adapter.submitList(data)
    }

    private fun showErrorMessage(message: String) {
        Snackbar.make(root, message, Snackbar.LENGTH_INDEFINITE).setAction(
            "refresh"
        ) {
            viewModel.refresh(subredditInput.text.toString())
        }.show()
    }
}
