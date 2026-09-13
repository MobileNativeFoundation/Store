@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain.meeseeks.docs

import dev.mattramotar.meeseeks.runtime.AppContext
import dev.mattramotar.meeseeks.runtime.BGTaskManager
import dev.mattramotar.meeseeks.runtime.Meeseeks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.mutations.MutationStore
import org.mobilenativefoundation.store6.mutations.drain.DrainConstraints
import org.mobilenativefoundation.store6.mutations.drain.DrainPolicy
import org.mobilenativefoundation.store6.mutations.drain.meeseeks.MeeseeksDrainScheduler
import org.mobilenativefoundation.store6.mutations.drain.meeseeks.StoreDrainPayload
import org.mobilenativefoundation.store6.mutations.drain.meeseeks.StoreDrainWorker
import org.mobilenativefoundation.store6.mutations.drain.mutationDrainCoordinator

/**
 * Compile-only JVM host wiring. This function is not a test and must not be invoked.
 * `Meeseeks.initialize` on Meeseeks 1.1.0 throws `NullPointerException` on the JVM.
 */
internal fun <K : StoreKey, V : Any> wireJvmHost(
    appContext: AppContext,
    users: MutationStore<K, V>,
    scope: CoroutineScope,
) {
    // docs:snippet:mutations-drain-meeseeks-jvm-wiring
    @OptIn(ExperimentalStoreApi::class)   // required: the whole module is experimental
    lateinit var bgTaskManager: BGTaskManager
    val drainScheduler = MeeseeksDrainScheduler(manager = { bgTaskManager })
    bgTaskManager =
        Meeseeks.initialize(appContext) {
            register<StoreDrainPayload> { workerContext ->
                StoreDrainWorker(workerContext, drainScheduler)
            }
        }
    val coordinator = mutationDrainCoordinator(drainScheduler)
    coordinator.register(
        "com.example.users",
        users,
        DrainPolicy(
            constraints = DrainConstraints(
                requiresNetwork = false,
                requiresCharging = false,
            ),
        ),
    )
    val watch = scope.launch { coordinator.watch("com.example.users") }
    scope.launch { coordinator.runActivation("com.example.users") }
    // docs:snippet:end
}
