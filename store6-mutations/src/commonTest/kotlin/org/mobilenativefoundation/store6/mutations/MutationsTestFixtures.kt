@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import app.cash.turbine.ReceiveTurbine
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.FetcherResult

/**
 * The module's single test key. The namespace parameter exists for the alias-facade tests
 * (D15a): a canonical target in another namespace must be constructible so cross-namespace
 * rejection and full-pair (not canonical-id) routing are provable; every other test keeps the
 * default `mutations` namespace.
 */
internal class MutationsTestKey(
    private val id: String,
    override val namespace: StoreNamespace = StoreNamespace("mutations"),
) : StoreKey {
    override fun canonicalId(): String = id
}

/** Exact-pair resolver for the module's single-namespace test key (D14). */
internal object MutationsTestKeyResolver : MutationKeyResolver<MutationsTestKey> {
    override suspend fun resolve(identity: MutationKeyIdentity): MutationsTestKey? =
        if (identity.namespace == "mutations") MutationsTestKey(identity.canonicalId) else null
}

/** UTF-8 string args codec for registrations whose args survive an encode/decode round trip. */
internal object FixtureStringArgsCodec : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): String = bytes.decodeToString()
}

/** Args codec for registrations whose codec is never exercised by the test at hand. */
internal fun <A : Any> inertArgsCodec(): MutationCodec<A> =
    object : MutationCodec<A> {
        override fun encode(value: A): ByteArray = ByteArray(0)

        override fun decode(
            version: Int,
            bytes: ByteArray,
        ): A = throw UnsupportedOperationException("This fixture codec does not decode.")
    }

/** Stale-set function for registrations that declare no invalidation effects. */
internal fun <K : StoreKey, A : Any> noStales(): (K, A) -> StaleSet<K> =
    { _, _ -> StaleSet(keys = emptySet(), namespaces = emptySet()) }

/**
 * Test-only dual-role backend for the mutations tracer.
 *
 * [MutationServer] intentionally has only push and retire. This fixture additionally exposes
 * [load] for a Store fetcher and [loadResult] for a `fetcherOfResult` door, so [offline] can
 * take the test's server reads and writes offline together. [pushBehavior] remains scriptable
 * for present-projection acknowledgements and failures using the `(key, value)` shape the
 * walking-skeleton tests script against — including a [MutationPresentAck] whose `canonicalKey`
 * redirects a provisional identity (D15a); [absentPushBehavior] scripts Absent-projection pushes
 * separately (D13: delete is drainable and its `mine` is `Absent`). A returned
 * [MutationAbsentAck] marks the entity deleted: the confirmed value is dropped and every fetch
 * begun afterwards observes [FetcherResult.Deleted] through [loadResult] — the backend
 * coherence obligation the ack certifies. Acknowledged state lands under the EFFECTIVE identity:
 * a Present ack carrying a canonical key stores the authoritative value at the canonical
 * identity, mirroring a backend whose entity truth lives at the canonical row. The library-built
 * [MutationPush] carrier is unwrapped before scripting.
 */
internal class FakeBackend(
    private val fallbackValue: String = "base",
) : MutationServer<MutationsTestKey, String> {
    private val confirmed = mutableMapOf<KeyIdentity, String>()
    private val deletedIdentities = mutableSetOf<KeyIdentity>()

    internal var offline: Boolean = false
    internal var fetchCount: Int = 0
        private set
    internal val pushedValues: MutableList<String> = mutableListOf()
    internal val receivedPushes: MutableList<MutationPush<MutationsTestKey, String>> = mutableListOf()
    internal var lastAck: MutationAck<MutationsTestKey, String>? = null
        private set

    /** Optional gate awaited inside [loadResult] after its entry snapshot is taken. */
    internal var loadGate: (suspend (MutationsTestKey) -> Unit)? = null

    internal var pushBehavior:
        suspend (MutationsTestKey, String) -> MutationAck<MutationsTestKey, String> = { _, value ->
            MutationPresentAck(
                authoritative = value,
                etag = "etag-${pushedValues.size}",
                canonicalKey = null,
            )
        }

    internal var absentPushBehavior:
        suspend (MutationsTestKey) -> MutationAck<MutationsTestKey, String> = { _ ->
            MutationAbsentAck(etag = "etag-${receivedPushes.size}")
        }

    internal fun seed(
        key: MutationsTestKey,
        value: String,
    ) {
        confirmed[key.identity()] = value
    }

    internal suspend fun load(key: MutationsTestKey): String {
        fetchCount += 1
        check(!offline) { "backend is offline" }
        return confirmed[key.identity()] ?: fallbackValue
    }

    /**
     * The rich-result fetch door. The result is snapshotted at entry — a fetch "begins" when it
     * reaches the backend — so a gated in-flight fetch keeps its pre-gate snapshot even when a
     * deletion is acknowledged while it waits (D13's begun-after semantics).
     */
    internal suspend fun loadResult(key: MutationsTestKey): FetcherResult<String> {
        fetchCount += 1
        check(!offline) { "backend is offline" }
        val identity = key.identity()
        val snapshot: FetcherResult<String> =
            if (identity in deletedIdentities) {
                FetcherResult.Deleted
            } else {
                FetcherResult.Success(confirmed[identity] ?: fallbackValue)
            }
        loadGate?.invoke(key)
        return snapshot
    }

    override suspend fun push(request: MutationPush<MutationsTestKey, String>): MutationAck<MutationsTestKey, String> {
        check(!offline) { "backend is offline" }
        receivedPushes += request
        val ack =
            when (val mine = request.mine) {
                is MutationPresence.Present -> {
                    pushedValues += mine.value
                    pushBehavior(request.key, mine.value)
                }
                MutationPresence.Absent -> absentPushBehavior(request.key)
            }
        when (ack) {
            is MutationPresentAck -> {
                // D15a: a canonical redirect means the entity's authoritative row IS the
                // canonical identity; the acknowledged value lands there.
                val effectiveIdentity = (ack.canonicalKey ?: request.key).identity()
                confirmed[effectiveIdentity] = ack.authoritative
                deletedIdentities -= effectiveIdentity
            }
            is MutationAbsentAck -> {
                val identity = request.key.identity()
                confirmed.remove(identity)
                deletedIdentities += identity
            }
        }
        lastAck = ack
        return ack
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(confirmedThroughSequence = request.retiredThroughSequence)
}

internal fun <K : StoreKey, V : Any> echoingMutationServer(): MutationServer<K, V> =
    object : MutationServer<K, V> {
        override suspend fun push(request: MutationPush<K, V>): MutationAck<K, V> =
            when (val mine = request.mine) {
                is MutationPresence.Present ->
                    MutationPresentAck(
                        authoritative = mine.value,
                        etag = null,
                        canonicalKey = null,
                    )
                MutationPresence.Absent -> MutationAbsentAck(etag = null)
            }

        override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
            MutationRetirementAck(confirmedThroughSequence = request.retiredThroughSequence)
    }

internal suspend fun ReceiveTurbine<StoreResult<String>>.awaitData(): StoreResult.Data<String> {
    while (true) {
        when (val result = awaitItem()) {
            is StoreResult.Data -> return result
            is StoreResult.Error,
            is StoreResult.Loading,
            is StoreResult.Revalidated,
            -> Unit
        }
    }
}

internal suspend fun ReceiveTurbine<StoreResult<String>>.awaitConfirmed(): StoreResult.Data<String> {
    while (true) {
        val result = awaitData()
        if (result.origin != Origin.OVERLAY) return result
    }
}
