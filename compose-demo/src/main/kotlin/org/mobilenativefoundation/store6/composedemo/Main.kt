@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.composedemo

import androidx.compose.ui.window.singleWindowApplication
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.store

fun main() {
    val controls = DemoControls()
    val users = store<UserKey, User> { fetcher(DemoFetcher(controls)) }
    // Process-scoped store on the bounded-registry engine: idle key engines
    // are LRU-bounded (default maxIdleKeys = 128 — this demo touches a single key, far under
    // the bound) and evicted only after quiescence. No explicit close() here by choice: the
    // window exit tears the JVM down, and core's close carries a GC-fallback posture.
    singleWindowApplication(title = "compose demo") {
        DemoScreen(users, controls)
    }
}
