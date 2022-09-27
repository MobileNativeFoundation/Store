package com.dropbox.notes.android.lib.presentation.navigation

interface Navigator<D : Destination<*>> {
    fun navigate(destination: D)
}