package com.dropbox.external.store5.concurrent

import kotlinx.coroutines.sync.Mutex

internal data class StoreSecurity(
    val writeRequestsLock: Mutex = Mutex(),
    val writeRequestsLightswitch: Lightswitch = Lightswitch(),

    val readCompletionsLock: Mutex = Mutex(),
    val readCompletionsLightswitch: Lightswitch = Lightswitch(),

    val broadcastLock: Mutex = Mutex(),
    val broadcastLightswitch: Lightswitch = Lightswitch(),
)