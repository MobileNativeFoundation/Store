package com.dropbox.android.external.store4.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

inline fun <T> T.listenerScoped(
    coroutineScope: CoroutineScope,
    crossinline register: (T?) -> Unit,
) {
    var weakListener: T? = this
    coroutineScope.launch(
        CoroutineExceptionHandler { _, throwable ->
            if (throwable is CancellationException) {
                weakListener = null
            }
        }
    ) {
        register.invoke(weakListener)
    }
}