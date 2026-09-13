@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain.internal

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import org.mobilenativefoundation.store6.mutations.MutationStore
import org.mobilenativefoundation.store6.mutations.drain.DrainPolicy

internal class DrainRegistration(
    val name: String,
    val store: MutationStore<*, *>,
    val policy: DrainPolicy,
    val epoch: Long,
    val job: Job,
    val passMutex: Mutex,
    val trackedActivation: MutableStateFlow<Boolean>,
    var derivationState: DerivationState,
)

internal class DrainRegistry {
    private class State(
        val registrations: Map<String, DrainRegistration>,
        val closed: Boolean,
        val nextEpoch: Long,
    )

    private val state =
        MutableStateFlow(
            State(
                registrations = emptyMap(),
                closed = false,
                nextEpoch = 0L,
            ),
        )

    fun register(
        name: String,
        store: MutationStore<*, *>,
        policy: DrainPolicy,
    ): DrainRegistration {
        while (true) {
            val current = state.value
            check(!current.closed) { "Drain registry is closed." }
            require(NAME_PATTERN.matches(name)) { "Invalid drain registration name: $name" }
            require(name !in current.registrations) {
                "Drain registration name is already registered: $name"
            }
            require(current.registrations.values.none { registration -> registration.store === store }) {
                "MutationStore is already registered."
            }

            val registration =
                DrainRegistration(
                    name = name,
                    store = store,
                    policy = policy,
                    epoch = current.nextEpoch,
                    job = Job(),
                    passMutex = Mutex(),
                    trackedActivation = MutableStateFlow(false),
                    derivationState =
                        DerivationState(
                            previousFingerprint = null,
                            noProgressPasses = 0,
                        ),
                )
            val updated =
                State(
                    registrations = current.registrations + (name to registration),
                    closed = false,
                    nextEpoch = current.nextEpoch + 1L,
                )
            if (state.compareAndSet(current, updated)) {
                return registration
            }
            registration.job.cancel()
        }
    }

    fun unregister(name: String): DrainRegistration? {
        while (true) {
            val current = state.value
            check(!current.closed) { "Drain registry is closed." }
            val registration = current.registrations[name] ?: return null
            val updated =
                State(
                    registrations = current.registrations - name,
                    closed = false,
                    nextEpoch = current.nextEpoch,
                )
            if (state.compareAndSet(current, updated)) {
                registration.job.cancel()
                return registration
            }
        }
    }

    fun get(name: String): DrainRegistration? = state.value.registrations[name]

    fun snapshot(): List<DrainRegistration> = state.value.registrations.values.toList()

    fun close(): List<DrainRegistration> {
        while (true) {
            val current = state.value
            if (current.closed) return emptyList()
            val registrations = current.registrations.values.toList()
            val updated =
                State(
                    registrations = emptyMap(),
                    closed = true,
                    nextEpoch = current.nextEpoch,
                )
            if (state.compareAndSet(current, updated)) {
                registrations.forEach { registration -> registration.job.cancel() }
                return registrations
            }
        }
    }

    val isClosed: Boolean
        get() = state.value.closed

    private companion object {
        val NAME_PATTERN: Regex = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
