@file:Suppress("UNCHECKED_CAST")

package com.dropbox.notes.android.lib.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

interface ViewModelFactory : ViewModelProvider.Factory {
    companion object {
        fun <VM : ViewModel> from(block: () -> VM): ViewModelFactory = RealViewModelFactory(block)
    }
}

private class RealViewModelFactory(
    val block: () -> ViewModel
) : ViewModelFactory {
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = block as VM
}