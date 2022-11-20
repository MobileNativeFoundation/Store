package org.mobilenativefoundation.store.notes.app

import android.app.Application
import org.mobilenativefoundation.store.notes.android.common.scoping.ComponentHolder
import org.mobilenativefoundation.store.notes.app.wiring.AppComponent
import org.mobilenativefoundation.store.notes.app.wiring.DaggerAppComponent

class NotesApp : Application(), ComponentHolder {
    override lateinit var component: AppComponent

    override fun onCreate() {
        super.onCreate()
        component = DaggerAppComponent.create()
    }
}