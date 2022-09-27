package com.dropbox.store.campaigns.android.app

import android.app.Application

class NotesApp : Application() {
    lateinit var component: AppComponent

    override fun onCreate() {
        super.onCreate()

        component = DaggerAppComponent.create()
    }
}