package com.nytimes.android.sample

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenResumed
import com.nytimes.android.external.store3.pipeline.StoreRequest
import com.nytimes.android.sample.reddit.PostAdapter
import kotlinx.android.synthetic.main.activity_store.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect

class RoomActivity : AppCompatActivity() {
    @ExperimentalCoroutinesApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_store)
        setSupportActionBar(findViewById<View>(R.id.toolbar) as Toolbar)
        initAdapter()
    }

    @ExperimentalCoroutinesApi
    private fun initAdapter() {
        val adapter = PostAdapter()
        postRecyclerView.adapter = adapter
        lifecycleScope.launchWhenStarted {
            val stream = (application as SampleApp).roomPipeline.stream(
                StoreRequest.cached(
                    key = "aww",
                    refresh = true
                )
            )
            stream.catch {
                // wait until UI shows up before showing an error and submitting an empty list
                whenResumed {
                    Toast
                        .makeText(this@RoomActivity, it.localizedMessage, Toast.LENGTH_LONG)
                        .show()
                }
                emit(emptyList())
            }.collect {
                adapter.submitList(it)
            }
        }
    }
}