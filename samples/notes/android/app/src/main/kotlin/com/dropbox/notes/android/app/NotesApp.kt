package com.dropbox.notes.android.app

import android.app.Application
import com.dropbox.notes.android.app.wiring.AppComponent
import com.dropbox.notes.android.app.wiring.DaggerAppComponent
import com.dropbox.notes.android.common.scoping.ComponentHolder

class NotesApp : Application(), ComponentHolder {
    override lateinit var component: AppComponent

    override fun onCreate() {
        super.onCreate()
        component = DaggerAppComponent.create()
    }
}