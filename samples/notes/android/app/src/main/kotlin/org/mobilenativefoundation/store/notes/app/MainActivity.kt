package org.mobilenativefoundation.store.notes.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.mobilenativefoundation.store.notes.android.common.scoping.ComponentHolder
import org.mobilenativefoundation.store.notes.app.ui.Scaffold
import org.mobilenativefoundation.store.notes.app.wiring.UserComponent

class MainActivity : ComponentActivity(), ComponentHolder {
    override lateinit var component: UserComponent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        component = (application as NotesApp).userComponent
        setContent { Scaffold() }
    }
}