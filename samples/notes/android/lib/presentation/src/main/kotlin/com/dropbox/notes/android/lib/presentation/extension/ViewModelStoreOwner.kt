package com.dropbox.store.campaigns.android.common.viewmodel.extension

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.dropbox.notes.android.lib.presentation.ViewModelFactory

inline fun <reified T : ViewModel> ViewModelStoreOwner.get(noinline block: () -> T): T =
    ViewModelProvider(this, ViewModelFactory.from(block))[T::class.java]