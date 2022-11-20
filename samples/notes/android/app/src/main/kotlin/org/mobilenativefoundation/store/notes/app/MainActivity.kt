package org.mobilenativefoundation.store.notes.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.mobilenativefoundation.store.notes.app.ui.Scaffold
import org.mobilenativefoundation.store.notes.app.wiring.AppDependencies
import org.mobilenativefoundation.store.notes.app.wiring.UserComponent
import org.mobilenativefoundation.store.notes.android.common.scoping.ComponentHolder
import org.mobilenativefoundation.store.notes.android.lib.result.Result

class MainActivity : ComponentActivity(), ComponentHolder {
    override lateinit var component: UserComponent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appComponent = (application as NotesApp).component
        val appDependencies = appComponent as AppDependencies
        val api = appDependencies.api
        val user = api.getUser(USER_ID)
        val userComponentFactory = (appComponent as UserComponent.ParentBindings).userComponentFactory()
        require(user is Result.Success)
        component = userComponentFactory.create(user.component1())
        setContent { Scaffold() }
    }

    companion object {
        private const val USER_ID = "1"
    }
}