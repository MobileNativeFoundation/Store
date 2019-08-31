package com.nytimes.android.sample

import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.widget.Toast.makeText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.nytimes.android.external.store3.base.impl.BarCode
import com.nytimes.android.external.store3.base.impl.Store
import com.nytimes.android.sample.data.model.Children
import com.nytimes.android.sample.data.model.Post
import com.nytimes.android.sample.data.model.RedditData
import com.nytimes.android.sample.reddit.PostAdapter
import com.squareup.moshi.Moshi
import kotlinx.android.synthetic.main.activity_store.*
import kotlinx.coroutines.*
import java.io.IOException
import java.lang.Exception
import kotlin.coroutines.CoroutineContext


class PersistingStoreActivity : AppCompatActivity() {
    lateinit var postAdapter: PostAdapter
    lateinit var persistedStore: Store<RedditData, BarCode>
    lateinit var moshi: Moshi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_store)
        setSupportActionBar(findViewById<View>(R.id.toolbar) as Toolbar)

        postAdapter = PostAdapter()
        postRecyclerView.adapter = postAdapter
        persistedStore = (applicationContext as SampleApp).nonPersistentPipielineStore
        moshi = (applicationContext as SampleApp).moshi
        loadPosts()
    }

    fun loadPosts() {
        lifecycleScope.launchWhenStarted {
            val awwRequest = BarCode(RedditData::class.java.simpleName, "aww")
            /*
            First call to get(awwRequest) will use the network, then save response in the in-memory
            cache. Subsequent calls will retrieve the cached version of the data.
             */
            val redditData = try {
                persistedStore.get(awwRequest)
            } catch (ioError : Throwable) {
                if (isActive) {
                    makeText(this@PersistingStoreActivity,
                            "Failed to load reddit posts: ${ioError.message}",
                            Toast.LENGTH_SHORT)
                            .show()
                }
                null
            } ?: return@launchWhenStarted
            showPosts(sanitizeData(redditData))
        }
    }

    private fun showPosts(posts: List<Post>) {
        postAdapter.submitList(posts)
        makeText(this@PersistingStoreActivity,
                "Loaded ${posts.size} posts",
                Toast.LENGTH_SHORT)
                .show()
    }

    fun sanitizeData(redditData: RedditData): List<Post> =
            redditData.data.children.map(Children::data)
}
