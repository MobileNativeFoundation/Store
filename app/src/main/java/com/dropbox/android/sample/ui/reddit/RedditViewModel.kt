package com.dropbox.android.sample.ui.reddit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.dropbox.android.external.store4.fresh
import com.dropbox.android.external.store4.get
import com.dropbox.android.sample.SampleApp
import com.dropbox.android.sample.data.model.Post
import com.dropbox.android.sample.utils.Lce
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class RedditViewModel(
    app: Application
) : AndroidViewModel(app) {
    private val store = (app as SampleApp).storeMultiParam
    private val configStore = (app as SampleApp).configStore

    val liveData = MutableLiveData<Lce<List<Post>>>(Lce.Success(emptyList()))

    fun refresh(key: String) {
        liveData.value = Lce.Loading
        viewModelScope.launch {
            liveData.value = try {
                val config = configStore.get(Unit)
                val data = store.fresh(key to config)
                Lce.Success(data)
            } catch (e: Exception) {
                Lce.Error(e)
            }
        }
    }
}
