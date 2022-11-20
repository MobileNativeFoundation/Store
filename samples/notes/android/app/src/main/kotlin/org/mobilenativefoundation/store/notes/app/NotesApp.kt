package org.mobilenativefoundation.store.notes.app

import android.app.Application
import com.squareup.anvil.annotations.ContributesBinding
import org.mobilenativefoundation.store.notes.android.common.scoping.AppScope
import org.mobilenativefoundation.store.notes.android.common.scoping.ComponentHolder
import org.mobilenativefoundation.store.notes.android.common.scoping.SingleIn
import org.mobilenativefoundation.store.notes.android.lib.result.Result
import org.mobilenativefoundation.store.notes.app.wiring.AppComponent
import org.mobilenativefoundation.store.notes.app.wiring.AppDependencies
import org.mobilenativefoundation.store.notes.app.wiring.DaggerAppComponent
import org.mobilenativefoundation.store.notes.app.wiring.UserComponent
import javax.inject.Inject

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = Application::class)
class NotesApp @Inject constructor() : Application(), ComponentHolder {
    override lateinit var component: AppComponent
    val userComponent: UserComponent by lazy {
        val appDependencies = component as AppDependencies
        val api = appDependencies.api
        val user = api.getUser(USER_ID)
        val userComponentFactory = (component as UserComponent.ParentBindings).userComponentFactory()
        require(user is Result.Success)
        userComponentFactory.create(user.component1())
    }

    override fun onCreate() {
        super.onCreate()
        component = DaggerAppComponent.create()
    }

    companion object {
        private const val USER_ID = "1"
    }
}