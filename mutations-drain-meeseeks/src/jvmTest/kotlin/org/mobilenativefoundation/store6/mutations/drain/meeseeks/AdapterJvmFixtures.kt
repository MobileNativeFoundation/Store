@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations.drain.meeseeks

import dev.mattramotar.meeseeks.runtime.AppContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.mutations.MutationAck
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationKeyIdentity
import org.mobilenativefoundation.store6.mutations.MutationKeyResolver
import org.mobilenativefoundation.store6.mutations.MutationPresence
import org.mobilenativefoundation.store6.mutations.MutationPresentAck
import org.mobilenativefoundation.store6.mutations.MutationPush
import org.mobilenativefoundation.store6.mutations.MutationRetirement
import org.mobilenativefoundation.store6.mutations.MutationRetirementAck
import org.mobilenativefoundation.store6.mutations.MutationServer
import org.mobilenativefoundation.store6.mutations.MutationStore
import org.mobilenativefoundation.store6.mutations.MutatorRef
import org.mobilenativefoundation.store6.mutations.StaleSet
import org.mobilenativefoundation.store6.mutations.drain.DrainBackoff
import org.mobilenativefoundation.store6.mutations.drain.DrainConstraints
import org.mobilenativefoundation.store6.mutations.drain.DrainPolicy
import org.mobilenativefoundation.store6.mutations.mutationStore
import org.mobilenativefoundation.store6.mutations.mutatorRegistry
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class AdapterJvmAppContext : AppContext()

internal class AdapterJvmKey(
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("drain")

    override fun canonicalId(): String = id
}

internal object AdapterJvmKeyResolver : MutationKeyResolver<AdapterJvmKey> {
    override suspend fun resolve(identity: MutationKeyIdentity): AdapterJvmKey? =
        if (identity.namespace == "drain") AdapterJvmKey(identity.canonicalId) else null
}

internal object AdapterJvmStringCodec : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): String = bytes.decodeToString()
}

internal class AdapterJvmBackend : MutationServer<AdapterJvmKey, String> {
    private val offlineState = AtomicBoolean(false)
    private val confirmed = ConcurrentHashMap<String, String>()
    private val pushGate = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val requestedRetirementSequence = AtomicLong(0L)

    internal var offline: Boolean
        get() = offlineState.get()
        set(value) {
            offlineState.set(value)
        }

    internal val pushAttempts: AtomicInteger = AtomicInteger()
    internal val receivedPushes: MutableList<String> = CopyOnWriteArrayList()
    internal var retireBehavior: suspend () -> MutationRetirementAck = {
        MutationRetirementAck(
            confirmedThroughSequence = requestedRetirementSequence.get(),
        )
    }

    internal suspend fun load(key: AdapterJvmKey): String =
        confirmed[key.canonicalId()] ?: "base"

    internal fun gateNextPush(): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        check(pushGate.compareAndSet(null, gate))
        return gate
    }

    override suspend fun push(
        request: MutationPush<AdapterJvmKey, String>,
    ): MutationAck<AdapterJvmKey, String> {
        pushAttempts.incrementAndGet()
        check(!offlineState.get()) { "backend is offline" }
        pushGate.getAndSet(null)?.await()
        val value =
            when (val mine = request.mine) {
                is MutationPresence.Present -> mine.value
                MutationPresence.Absent -> error("AdapterJvmBackend expects a present mutation.")
            }
        receivedPushes += value
        confirmed[request.key.canonicalId()] = value
        return MutationPresentAck(
            authoritative = value,
            etag = null,
            canonicalKey = null,
        )
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck {
        requestedRetirementSequence.set(request.retiredThroughSequence)
        return retireBehavior()
    }
}

internal class AdapterJvmFixture {
    internal val backend: AdapterJvmBackend = AdapterJvmBackend()
    internal lateinit var appendRef: MutatorRef<AdapterJvmKey, String, String>
        private set

    private val storage = InMemoryMutationJournalStorage()
    private val registry =
        mutatorRegistry<AdapterJvmKey, String> {
            appendRef =
                mutator(
                    id = "drain-append",
                    version = 1,
                    codec = AdapterJvmStringCodec,
                    stales = { _, _ -> StaleSet(emptySet(), emptySet()) },
                ) { base, suffix ->
                    MutationPresence.Present(
                        ((base as? MutationPresence.Present)?.value).orEmpty() + suffix,
                    )
                }
        }

    internal fun openStore(): MutationStore<AdapterJvmKey, String> =
        mutationStore(
            registry = registry,
            server = backend,
            keyResolver = AdapterJvmKeyResolver,
            valueCodecVersion = 1,
            valueCodec = AdapterJvmStringCodec,
        ) {
            fetcher { backend.load(it) }
            journalStorage(storage)
        }
}

internal fun adapterJvmDrainPolicy(
    drainOnEnqueue: Boolean = true,
): DrainPolicy =
    DrainPolicy(
        constraints =
            DrainConstraints(
                requiresNetwork = false,
                requiresCharging = false,
            ),
        backoff =
            DrainBackoff(
                initialDelay = 200.milliseconds,
                maxDelay = 2.seconds,
            ),
        drainOnEnqueue = drainOnEnqueue,
    )

