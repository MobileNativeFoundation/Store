package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private const val REENTRY_MESSAGE =
    "MaintenanceCoordinator callbacks cannot re-enter the same coordinator."

private class MaintenanceCallbackContext(
    val coordinator: MaintenanceCoordinator,
    val parent: MaintenanceCallbackContext?,
) : AbstractCoroutineContextElement(Key) {
    fun contains(candidate: MaintenanceCoordinator): Boolean {
        var current: MaintenanceCallbackContext? = this
        while (current != null) {
            if (current.coordinator === candidate) return true
            current = current.parent
        }
        return false
    }

    companion object Key : CoroutineContext.Key<MaintenanceCallbackContext>
}

internal class MaintenanceCoordinator {
    private val stateMutex = Mutex()
    private val maintenanceMutex = Mutex()
    private val wakeVersion = MutableStateFlow(0L)
    private val activeCommits = mutableMapOf<String, Int>()
    private val blockedNamespaces = mutableSetOf<String>()
    private var globalMaintenance = false

    suspend fun <T> withCommit(
        namespace: String,
        block: suspend () -> T,
    ): T {
        val callbackContext = callbackContextForEntry()
        acquireCommit(namespace)
        try {
            return withContext(callbackContext) { block() }
        } finally {
            withContext(NonCancellable) {
                releaseCommit(namespace)
            }
        }
    }

    suspend fun <T> withNamespaceMaintenance(
        namespace: String,
        block: suspend () -> T,
    ): T =
        withMaintenance(
            namespace = namespace,
            callbackContext = callbackContextForEntry(),
            block = block,
        )

    suspend fun <T> withGlobalMaintenance(block: suspend () -> T): T =
        withMaintenance(
            namespace = null,
            callbackContext = callbackContextForEntry(),
            block = block,
        )

    private suspend fun callbackContextForEntry(): MaintenanceCallbackContext {
        val parent = currentCoroutineContext()[MaintenanceCallbackContext]
        check(parent?.contains(this) != true) { REENTRY_MESSAGE }
        return MaintenanceCallbackContext(coordinator = this, parent = parent)
    }

    private suspend fun acquireCommit(namespace: String) {
        while (true) {
            var admitted = false
            val observedVersion =
                stateMutex.withLock {
                    if (!globalMaintenance && namespace !in blockedNamespaces) {
                        activeCommits[namespace] = activeCommits.getOrElse(namespace) { 0 } + 1
                        advanceVersion()
                        admitted = true
                    }
                    wakeVersion.value
                }
            if (admitted) return
            wakeVersion.first { it != observedVersion }
        }
    }

    private suspend fun releaseCommit(namespace: String) {
        stateMutex.withLock {
            val active = checkNotNull(activeCommits[namespace])
            if (active == 1) {
                activeCommits.remove(namespace)
            } else {
                activeCommits[namespace] = active - 1
            }
            advanceVersion()
        }
    }

    private suspend fun <T> withMaintenance(
        namespace: String?,
        callbackContext: MaintenanceCallbackContext,
        block: suspend () -> T,
    ): T {
        maintenanceMutex.lock()
        var scopeBlocked = false
        try {
            blockScope(namespace)
            scopeBlocked = true
            awaitDrain(namespace)
            return withContext(callbackContext) { block() }
        } finally {
            withContext(NonCancellable) {
                if (scopeBlocked) {
                    unblockScope(namespace)
                }
                maintenanceMutex.unlock()
            }
        }
    }

    private suspend fun blockScope(namespace: String?) {
        stateMutex.withLock {
            if (namespace == null) {
                globalMaintenance = true
            } else {
                check(blockedNamespaces.add(namespace))
            }
            advanceVersion()
        }
    }

    private suspend fun awaitDrain(namespace: String?) {
        while (true) {
            val observedVersion =
                stateMutex.withLock {
                    val drained =
                        if (namespace == null) {
                            activeCommits.isEmpty()
                        } else {
                            namespace !in activeCommits
                        }
                    if (drained) null else wakeVersion.value
                }
            if (observedVersion == null) return
            wakeVersion.first { it != observedVersion }
        }
    }

    private suspend fun unblockScope(namespace: String?) {
        stateMutex.withLock {
            if (namespace == null) {
                globalMaintenance = false
            } else {
                check(blockedNamespaces.remove(namespace))
            }
            advanceVersion()
        }
    }

    private fun advanceVersion() {
        wakeVersion.value += 1L
    }
}
