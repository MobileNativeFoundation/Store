package com.dropbox.notes.android.common.scoping

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

class ComponentHolderViewModel(application: Application) : AndroidViewModel(application) {
    val map = ConcurrentHashMap<Class<*>, Any>()

    inline fun <reified T : Any> get(factory: (scope: CoroutineScope, application: Application) -> T): T =
        map.getOrPut(T::class.java) { factory(viewModelScope, getApplication()) } as T
}




