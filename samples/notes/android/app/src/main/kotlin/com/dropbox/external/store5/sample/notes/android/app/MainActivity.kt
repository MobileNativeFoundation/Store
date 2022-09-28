package com.dropbox.external.store5.sample.notes.android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dropbox.external.store5.sample.notes.android.app.wiring.DaggerAppComponent
import com.dropbox.external.store5.sample.notes.android.common.scoping.ComponentHolder

class MainActivity : ComponentActivity(), ComponentHolder {

    override lateinit var component: Any

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        component = DaggerAppComponent.create()
        setContent { }
    }
}