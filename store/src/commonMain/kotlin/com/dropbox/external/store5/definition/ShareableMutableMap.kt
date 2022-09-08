package com.dropbox.external.store5.definition

import co.touchlab.stately.isolate.IsolateState

typealias ShareableMutableMap<Key, Value> = IsolateState<MutableMap<Key, Value>>