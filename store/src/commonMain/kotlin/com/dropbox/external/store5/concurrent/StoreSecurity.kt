package com.dropbox.external.store5.concurrent

import kotlinx.coroutines.sync.Semaphore

internal data class StoreSecurity(
    val writeRequestsLock: Semaphore = Semaphore(1),
    val writeRequestsLightswitch: Lightswitch = Lightswitch(),

    val readCompletionsLock: Semaphore = Semaphore(1),
    val readCompletionsLightswitch: Lightswitch = Lightswitch(),

    val broadcastLock: Semaphore = Semaphore(1),
    val broadcastLightswitch: Lightswitch = Lightswitch(),
)