package com.dropbox.notes.android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dropbox.notes.android.app.ui.Scaffold
import com.dropbox.notes.android.app.wiring.AppDependencies
import com.dropbox.notes.android.app.wiring.UserComponent
import com.dropbox.notes.android.common.scoping.ComponentHolder
import com.dropbox.notes.android.lib.result.Result

class MainActivity : ComponentActivity(), ComponentHolder {
    override lateinit var component: UserComponent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appComponent = (application as NotesApp).component
        val appDependencies = appComponent as AppDependencies
        val api = appDependencies.api
        val user = api.getUser()
        val userComponentFactory = (appComponent as UserComponent.ParentBindings).userComponentFactory()
        require(user is Result.Success)
        component = userComponentFactory.create(user.component1())
        setContent { Scaffold() }
    }
}