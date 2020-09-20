package com.dropbox.android.sample.ui.uistate

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.UIController
import com.dropbox.android.sample.R
import kotlinx.android.synthetic.main.ui_state.buttonRefresh
import kotlinx.android.synthetic.main.ui_state.loadingInfo
import kotlinx.android.synthetic.main.ui_state.streamValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow


class UIStateFragment : Fragment() {
    val store = StoreBuilder.from<Int, Int>(
            fetcher = Fetcher.ofFlow {key:Int ->
                flow {
                    var latest = key
                    while(true) {
                        delay(2000)
                        emit(latest)
                        latest += 2

                    }
                }
            }
    ).build()
    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.ui_state, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val controller = UIController(
                store = store,
                request = StoreRequest.fresh(3)
        )
        buttonRefresh.setOnClickListener {
            controller.refresh()
        }
        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            controller.state.collect {
                streamValue.text = "${it.data?.dataOrNull()}"
                loadingInfo.text = "loading: ${it.isLoading()}"
            }
        }
    }
}