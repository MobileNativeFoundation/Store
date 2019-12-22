package com.dropbox.android.sample

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Observer
import com.dropbox.android.sample.data.model.Post
import com.dropbox.android.sample.reddit.PostAdapter
import com.dropbox.android.sample.utils.Lce
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_room_store.*
import kotlinx.android.synthetic.main.activity_store.postRecyclerView

class RedditActivity : AppCompatActivity() {

    private val viewModel: RedditViewModel by viewModels()

    private val adapter = PostAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_room_store)

        setSupportActionBar(findViewById<View>(R.id.toolbar) as Toolbar)

        pullToRefresh.setOnRefreshListener {
            viewModel.refresh(subredditInput.text.toString())
        }
        fetchButton.setOnClickListener {
            viewModel.refresh(subredditInput.text.toString())
        }
        viewModel.liveData.observe(this, Observer { lce: Lce<List<Post>> ->
            pullToRefresh.isRefreshing = lce is Lce.Loading
            when (lce) {
                is Lce.Error -> showErrorMessage(lce.message)
                is Lce.Success -> updateData(lce.data)
            }
        })
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