package com.dropbox.external.store5.operator

import co.touchlab.stately.isolate.IsolateState
import com.dropbox.external.store5.definition.ShareableMutableMap

internal inline fun <Key : Any, Value : Any> shareableMutableMapOf():
    ShareableMutableMap<Key, Value> = IsolateState { mutableMapOf() }