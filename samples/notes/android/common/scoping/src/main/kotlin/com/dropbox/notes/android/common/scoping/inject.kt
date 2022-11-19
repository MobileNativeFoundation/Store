package com.dropbox.notes.android.common.scoping

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
inline fun <reified T : Any> inject(context: Context = LocalContext.current): T {
    val activity = requireActivity(context = context) as ComponentHolder
    return activity.component as T
}

@Composable
fun requireActivity(context: Context): ComponentActivity {
    var currentContext = context

    if (currentContext is ComponentActivity) {
        return currentContext
    } else {
        while (currentContext is ContextWrapper) {
            if (currentContext is ComponentActivity) {
                return currentContext
            }
            currentContext = currentContext.baseContext
        }
    }

    throw Exception()
}
