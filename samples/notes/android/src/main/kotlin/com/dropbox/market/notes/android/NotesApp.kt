package com.dropbox.market.notes.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp


@HiltAndroidApp
class NotesApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}