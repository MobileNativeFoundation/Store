package com.dropbox.android.sample.reddit

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.dropbox.android.sample.R
import com.dropbox.android.sample.data.model.Post
import com.squareup.picasso.Picasso
import kotlinx.android.synthetic.main.article_item.view.*

class PostViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    fun onBind(article: Post) {
        itemView.title!!.text = article.title
        article.nestedThumbnail()?.url?.let { showImage(it) }
    }

    private fun showImage(url: String) {
        Picasso.with(itemView.context)
                .load(url)
                .placeholder(R.color.gray80)
                .into(itemView.thumbnail)
    }
}
