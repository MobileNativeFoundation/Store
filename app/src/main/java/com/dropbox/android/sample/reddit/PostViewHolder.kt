package com.dropbox.android.sample.reddit

import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.dropbox.android.sample.R
import com.dropbox.android.sample.data.model.Post
import com.squareup.picasso.Picasso
import kotlinx.android.synthetic.main.article_item.view.*

class PostViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    fun onBind(article: Post) {
        itemView.title!!.text = article.title
        val url = article.nestedThumbnail()?.url
        itemView.thumbnail.isVisible = url != null
        url?.let { showImage(it) }
    }

    private fun showImage(url: String) {
        Picasso.get()
            .load(url)
            .placeholder(R.color.gray80)
            .into(itemView.thumbnail)
    }
}
