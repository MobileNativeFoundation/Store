package com.dropbox.notes.android.common.scoping

import android.app.Application
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope

inline fun <reified T : Any> Fragment.fragmentComponent(
    crossinline factory: (scope: CoroutineScope, application: Application) -> T
): Lazy<ComponentHolderViewModel> = lazy { ViewModelProvider(this)[ComponentHolderViewModel::class.java] }
