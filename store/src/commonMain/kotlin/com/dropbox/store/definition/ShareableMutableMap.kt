package com.dropbox.store.definition

import co.touchlab.stately.isolate.IsolateState

typealias ShareableMutableMap<Key, Value> = IsolateState<MutableMap<Key, Value>>