package com.dropbox.store.operator

import co.touchlab.stately.isolate.IsolateState
import com.dropbox.store.definition.ShareableMutableMap

internal inline fun <Key : Any, Value : Any> shareableMutableMapOf():
    ShareableMutableMap<Key, Value> = IsolateState { mutableMapOf() }