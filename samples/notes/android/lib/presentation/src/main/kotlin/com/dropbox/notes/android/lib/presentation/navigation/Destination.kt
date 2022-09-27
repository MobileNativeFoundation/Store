package com.dropbox.notes.android.lib.presentation.navigation

interface Destination<out Id : Any> {
    val id: Id
}