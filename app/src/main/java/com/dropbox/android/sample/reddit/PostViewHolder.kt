package com.dropbox.android.sample.reddit

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.dropbox.android.sample.R
import com.dropbox.android.sample.data.model.Post
import com.squareup.picasso.Picasso

class PostViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    private val title: TextView get() = itemView.findViewById(R.id.title)

    private val thumbnail: ImageView get() = itemView.findViewById(R.id.thumbnail)

    fun onBind(article: Post) {
        title.text = article.title
        val url = article.nestedThumbnail()?.url
        thumbnail.isVisible = url != null
        url?.let { showImage(it) }
    }

    private fun showImage(url: String) {
        Picasso.with(itemView.context)
            .load(url)
            .placeholder(R.color.gray80)
            .into(thumbnail)
    }
}
