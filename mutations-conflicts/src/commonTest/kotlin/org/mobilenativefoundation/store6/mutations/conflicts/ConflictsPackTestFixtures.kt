@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.conflicts

import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.StoreResults
import org.mobilenativefoundation.store6.mutations.MutationAbsentAck
import org.mobilenativefoundation.store6.mutations.MutationAck
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationConflictBuilder
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
import org.mobilenativefoundation.store6.mutations.MutatorRegistry
import org.mobilenativefoundation.store6.mutations.StaleSet
import org.mobilenativefoundation.store6.mutations.mutationStore
import org.mobilenativefoundation.store6.mutations.mutatorRegistry
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.testing.TestWallClock
import kotlin.test.fail

private const val CONFLICTS_PACK_NAMESPACE: String = "conflicts-pack"

internal class ConflictsPackKey(
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace(CONFLICTS_PACK_NAMESPACE)

    override fun canonicalId(): String = id
}

internal object ConflictsPackKeyResolver : MutationKeyResolver<ConflictsPackKey> {
    override suspend fun resolve(identity: MutationKeyIdentity): ConflictsPackKey? =
        if (identity.namespace == CONFLICTS_PACK_NAMESPACE) {
            ConflictsPackKey(identity.canonicalId)
        } else {
            null
        }
}

internal object ConflictsPackStringCodec : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): String = bytes.decodeToString()
}

internal data class Stamped(
    val text: String,
    val writtenAtEpochMillis: Long,
)

/** Encodes as `"$writtenAtEpochMillis:$text"`; decode splits on the first colon. */
internal object ConflictsPackStampedCodec : MutationCodec<Stamped> {
    override fun encode(value: Stamped): ByteArray =
        "${value.writtenAtEpochMillis}:${value.text}".encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): Stamped {
        val encoded = bytes.decodeToString()
        val separator = encoded.indexOf(':')
        require(separator >= 0) { "Stamped encoding requires epochMillis:text; was $encoded." }
        return Stamped(
            text = encoded.substring(separator + 1),
            writtenAtEpochMillis = encoded.substring(0, separator).toLong(),
        )
    }
}

internal val stringUpsert: MutatorRef<ConflictsPackKey, String, String>
    get() = StringConflictsInit.upsert

internal val stringRegistry: MutatorRegistry<ConflictsPackKey, String>
    get() = StringConflictsInit.registry

internal val stampedUpsert: MutatorRef<ConflictsPackKey, Stamped, Stamped>
    get() = StampedConflictsInit.upsert

internal val stampedDelete: MutatorRef<ConflictsPackKey, Stamped, Unit>
    get() = StampedConflictsInit.delete

internal val stampedRegistry: MutatorRegistry<ConflictsPackKey, Stamped>
    get() = StampedConflictsInit.registry

private object StringConflictsInit {
    lateinit var upsert: MutatorRef<ConflictsPackKey, String, String>
    val registry: MutatorRegistry<ConflictsPackKey, String> =
        mutatorRegistry {
            upsert =
                upsert(
                    id = "upsert",
                    version = 1,
                    codec = ConflictsPackStringCodec,
                    stales = { key, _ ->
                        StaleSet(keys = setOf(key), namespaces = emptySet())
                    },
                ) { _, args -> MutationPresence.Present(args) }
        }
}

private object StampedConflictsInit {
    lateinit var upsert: MutatorRef<ConflictsPackKey, Stamped, Stamped>
    lateinit var delete: MutatorRef<ConflictsPackKey, Stamped, Unit>
    val registry: MutatorRegistry<ConflictsPackKey, Stamped> =
        mutatorRegistry {
            upsert =
                upsert(
                    id = "upsert",
                    version = 1,
                    codec = ConflictsPackStampedCodec,
                    stales = { _, _ ->
                        StaleSet(keys = emptySet(), namespaces = emptySet())
                    },
                ) { _, args -> MutationPresence.Present(args) }
            delete =
                delete(
                    id = "delete",
                    stales = { _, _ ->
                        StaleSet(keys = emptySet(), namespaces = emptySet())
                    },
                )
        }
}

internal class ConflictsPackBackend<V : Any>(
    private val fallbackValue: V? = null,
) : MutationServer<ConflictsPackKey, V> {
    private val confirmed = mutableMapOf<BackendIdentity, V>()
    private val deletedIdentities = mutableSetOf<BackendIdentity>()

    val receivedPushes: MutableList<MutationPush<ConflictsPackKey, V>> = mutableListOf()

    var pushBehavior: suspend (MutationPush<ConflictsPackKey, V>) -> MutationAck<ConflictsPackKey, V> =
        { request ->
            when (val mine = request.mine) {
                is MutationPresence.Present ->
                    MutationPresentAck(
                        authoritative = mine.value,
                        etag = "etag-${receivedPushes.size}",
                        canonicalKey = null,
                    )
                MutationPresence.Absent -> MutationAbsentAck(etag = "etag-${receivedPushes.size}")
            }
        }

    // Retirement stays unconfirmed so rows survive pruning.
    var retireBehavior: suspend (MutationRetirement) -> MutationRetirementAck = {
        MutationRetirementAck(confirmedThroughSequence = 0L)
    }

    fun seed(
        key: ConflictsPackKey,
        value: V,
    ) {
        val identity = BackendIdentity(key)
        confirmed[identity] = value
        deletedIdentities -= identity
    }

    fun seedDeleted(key: ConflictsPackKey) {
        val identity = BackendIdentity(key)
        confirmed.remove(identity)
        deletedIdentities += identity
    }

    fun loadResult(key: ConflictsPackKey): FetcherResult<V> {
        val identity = BackendIdentity(key)
        return when {
            identity in deletedIdentities -> FetcherResult.Deleted
            identity in confirmed -> FetcherResult.Success(confirmed.getValue(identity))
            fallbackValue != null -> FetcherResult.Success(fallbackValue)
            else ->
                FetcherResult.Error(
                    StoreResults.exception(StoreResults.missing(key, "unseeded"), null),
                )
        }
    }

    override suspend fun push(
        request: MutationPush<ConflictsPackKey, V>,
    ): MutationAck<ConflictsPackKey, V> {
        receivedPushes += request
        val ack = pushBehavior(request)
        when (ack) {
            is MutationPresentAck -> {
                val effective = BackendIdentity(ack.canonicalKey ?: request.key)
                confirmed[effective] = ack.authoritative
                deletedIdentities -= effective
            }
            is MutationAbsentAck -> {
                val identity = BackendIdentity(request.key)
                confirmed.remove(identity)
                deletedIdentities += identity
            }
        }
        return ack
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        retireBehavior(request)
}

internal fun conflictException(
    meta: StoreMeta?,
    message: String = "conflict",
    cause: Throwable = IllegalStateException("backend conflict cause"),
): StoreException = StoreResults.exception(StoreResults.conflict(meta, message), cause)

internal class ConflictsPackMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String?,
) : StoreMeta

internal fun openStringStore(
    storage: MutationJournalStorage,
    server: ConflictsPackBackend<String>,
    clock: TestWallClock,
    configurer: (MutationConflictBuilder<ConflictsPackKey, String>.() -> Unit)? = null,
): MutationStore<ConflictsPackKey, String> =
    mutationStore(
        registry = stringRegistry,
        server = server,
        keyResolver = ConflictsPackKeyResolver,
        valueCodecVersion = 1,
        valueCodec = ConflictsPackStringCodec,
    ) {
        fetcherOfResult { key -> server.loadResult(key) }
        journalStorage(storage)
        wallClock(clock)
        if (configurer != null) {
            conflicts(configurer)
        }
    }

internal fun openStampedStore(
    storage: MutationJournalStorage,
    server: ConflictsPackBackend<Stamped>,
    clock: TestWallClock,
    configurer: (MutationConflictBuilder<ConflictsPackKey, Stamped>.() -> Unit)? = null,
): MutationStore<ConflictsPackKey, Stamped> =
    mutationStore(
        registry = stampedRegistry,
        server = server,
        keyResolver = ConflictsPackKeyResolver,
        valueCodecVersion = 1,
        valueCodec = ConflictsPackStampedCodec,
    ) {
        fetcherOfResult { key -> server.loadResult(key) }
        journalStorage(storage)
        wallClock(clock)
        if (configurer != null) {
            conflicts(configurer)
        }
    }

internal suspend fun capturedClientId(
    server: ConflictsPackBackend<*>,
    storage: MutationJournalStorage,
): String {
    val clientId =
        server.receivedPushes.firstOrNull()?.clientId
            ?: fail("No mutation push was recorded; cannot capture client id.")
    storage.transaction { transaction ->
        checkNotNull(transaction.client(clientId)) {
            "Journal has no client row for captured id '$clientId'."
        }
    }
    return clientId
}

private data class BackendIdentity(
    val namespace: String,
    val canonicalId: String,
) {
    constructor(key: StoreKey) : this(key.namespace.value, key.canonicalId())
}
