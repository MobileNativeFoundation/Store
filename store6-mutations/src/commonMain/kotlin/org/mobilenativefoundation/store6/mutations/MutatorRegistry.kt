@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey

internal class MutatorRegistryOwnership

/** The fixed args-codec version used by every `delete` registration. */
internal const val MUTATION_DELETE_ARGS_VERSION: Int = 1

/**
 * The one deliberate args-codec specialization: Store6 owns the `delete` codec at fixed
 * version 1, and its encoding is exactly an empty byte array. Durable delete rows with another
 * args version or non-empty args bytes are a `CODEC` failure; this codec rejects them at decode
 * so the engine normalizes the throw into a `CODEC` failure record.
 */
internal object MutationUnitArgsCodec : MutationCodec<Unit> {
    override fun encode(value: Unit): ByteArray = ByteArray(0)

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ) {
        require(version == MUTATION_DELETE_ARGS_VERSION) {
            "Delete args are fixed at version $MUTATION_DELETE_ARGS_VERSION; was $version."
        }
        require(bytes.isEmpty()) {
            "Delete args encode zero bytes; received ${bytes.size}."
        }
    }
}

/**
 * A typed reference to a registered durable mutator.
 *
 * The three type parameters keep key, value, and args compile-time bound at Kotlin call sites.
 */
@ExperimentalStoreApi
public class MutatorRef<K : StoreKey, V : Any, A : Any> internal constructor(
    /** The stable mutator storage identity persisted with pending intents. */
    @ExperimentalStoreApi
    public val id: String,
    internal val ownership: MutatorRegistryOwnership,
)

/**
 * One erased registration: the single place where typed registration lambdas meet the erased
 * dispatch used by the journal and engine. Args re-enter their registered type only through
 * codec decode or the contained registration-time cast.
 */
internal class MutatorRegistration<K : StoreKey, V : Any>(
    internal val id: String,

    /** The registered args codec/schema version; positive, persisted with each intent. */
    internal val argsVersion: Int,
    private val encodeErased: (Any) -> ByteArray,
    private val decodeErased: (Int, ByteArray) -> Any,
    private val stalesErased: (K, Any) -> StaleSet<K>,
    private val projectErased: (MutationPresence<V>, Any) -> MutationPresence<V>?,
) {
    /** Encodes [args]; output is defensively copied before any retention. */
    internal fun encodeArgs(args: Any): ByteArray = encodeErased(args).copyOf()

    /** Decodes stored args bytes; input is defensively copied before consumer code. */
    internal fun decodeArgs(
        version: Int,
        bytes: ByteArray,
    ): Any = decodeErased(version, bytes.copyOf())

    /** Evaluates the pure registered invalidation function. */
    internal fun stales(
        key: K,
        args: Any,
    ): StaleSet<K> = stalesErased(key, args)

    /** Projects [base] through the registered mutator; `null` means decline only. */
    internal fun project(
        base: MutationPresence<V>,
        args: Any,
    ): MutationPresence<V>? = projectErased(base, args)
}

/**
 * The durable mutation projections available to a mutation engine.
 *
 * Registration is compile-time typed; storage identity is the registered id plus args version.
 * Enqueue validates [MutatorRef] ownership against this registry before any journal append.
 */
@ExperimentalStoreApi
public class MutatorRegistry<K : StoreKey, V : Any> internal constructor(
    internal val ownership: MutatorRegistryOwnership,
    internal val registrations: Map<String, MutatorRegistration<K, V>>,
)

/**
 * Registers typed durable mutators.
 *
 * Every registration is durable and named: no call-site closure becomes a durable intent. Each
 * non-delete mutator supplies an explicit args version and [MutationCodec]; `delete` is the one
 * specialization and owns a fixed module-internal version-1 Unit codec encoding zero bytes.
 */
@ExperimentalStoreApi
public class MutatorRegistryBuilder<K : StoreKey, V : Any> internal constructor() {
    private val ownership = MutatorRegistryOwnership()
    private val registrations = mutableMapOf<String, MutatorRegistration<K, V>>()
    private var isBuilt = false

    /**
     * Registers the generic mutator shape under [id] and returns the typed reference used to
     * enqueue its arguments.
     *
     * [project] runs synchronously inside Store's Overlay application and may be invoked
     * repeatedly or concurrently for different keys. It must be a pure, deterministic,
     * non-blocking function of `(base, args)`, and it must not call back into Store. Returning
     * `null` means exactly "decline this intent"; a declined head remains pending and
     * blocks only its same-effective-key suffix. Deletion is [MutationPresence.Absent], never
     * `null`. A thrown failure is contained, reported through `MutationStore.poisoned`, and
     * parks the row with a normalized `PROJECTION` failure.
     *
     * [stales] is the pure declarative invalidation function: equal inputs must produce
     * structurally equal [StaleSet]s. Its result is copied, normalized, deduplicated, and sorted
     * into immutable effect records before the intent's first push.
     *
     * [version] is the explicit args codec/schema version persisted with each intent; it must be
     * positive. [codec] must be pure and deterministic for that version.
     */
    @ExperimentalStoreApi
    public fun <A : Any> mutator(
        id: String,
        version: Int,
        codec: MutationCodec<A>,
        stales: (K, A) -> StaleSet<K>,
        project: (MutationPresence<V>, A) -> MutationPresence<V>?,
    ): MutatorRef<K, V, A> {
        require(!isBuilt) {
            "MutatorRegistryBuilder is already built."
        }
        require(id !in registrations) {
            "Mutator id '$id' is already registered."
        }
        require(version >= 1) {
            "Mutator '$id' args version must be positive; was $version."
        }

        @Suppress("UNCHECKED_CAST")
        val registration =
            MutatorRegistration<K, V>(
                id = id,
                argsVersion = version,
                encodeErased = { args -> codec.encode(args as A) },
                decodeErased = { decodeVersion, bytes -> codec.decode(decodeVersion, bytes) },
                stalesErased = { key, args -> stales(key, args as A) },
                projectErased = { base, args -> project(base, args as A) },
            )
        registrations[id] = registration
        return MutatorRef(id, ownership)
    }

    /**
     * Registers an update mutator: [project] transforms an existing value, and the registration
     * declines when the confirmed base is [MutationPresence.Absent].
     */
    @ExperimentalStoreApi
    public fun <A : Any> update(
        id: String,
        version: Int,
        codec: MutationCodec<A>,
        stales: (K, A) -> StaleSet<K>,
        project: (V, A) -> V,
    ): MutatorRef<K, V, A> =
        mutator(id, version, codec, stales) { base, args ->
            when (base) {
                is MutationPresence.Present -> MutationPresence.Present(project(base.value, args))
                MutationPresence.Absent -> null
            }
        }

    /**
     * Registers a create mutator: [project] builds the new value from args alone and the
     * confirmed base is ignored.
     */
    @ExperimentalStoreApi
    public fun <A : Any> create(
        id: String,
        version: Int,
        codec: MutationCodec<A>,
        stales: (K, A) -> StaleSet<K>,
        project: (A) -> V,
    ): MutatorRef<K, V, A> =
        mutator(id, version, codec, stales) { _, args ->
            MutationPresence.Present(project(args))
        }

    /**
     * Registers a delete mutator: it always applies [MutationPresence.Absent] and is drainable.
     * It accepts neither a version nor a codec; Store6 owns the fixed version-1 Unit codec whose
     * encoding is exactly zero bytes.
     */
    @ExperimentalStoreApi
    public fun delete(
        id: String,
        stales: (K, Unit) -> StaleSet<K>,
    ): MutatorRef<K, V, Unit> =
        mutator(id, MUTATION_DELETE_ARGS_VERSION, MutationUnitArgsCodec, stales) { _, _ ->
            MutationPresence.Absent
        }

    /**
     * Registers an upsert mutator: [project] receives the explicit confirmed
     * [MutationPresence] and must return a presence — it cannot decline.
     */
    @ExperimentalStoreApi
    public fun <A : Any> upsert(
        id: String,
        version: Int,
        codec: MutationCodec<A>,
        stales: (K, A) -> StaleSet<K>,
        project: (MutationPresence<V>, A) -> MutationPresence<V>,
    ): MutatorRef<K, V, A> = mutator(id, version, codec, stales, project)

    internal fun build(): MutatorRegistry<K, V> {
        require(!isBuilt) {
            "MutatorRegistryBuilder is already built."
        }
        isBuilt = true
        return MutatorRegistry(
            ownership = ownership,
            registrations = registrations.toMap(),
        )
    }
}

/** Builds a registry of typed durable mutators. */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> mutatorRegistry(
    configure: MutatorRegistryBuilder<K, V>.() -> Unit,
): MutatorRegistry<K, V> = MutatorRegistryBuilder<K, V>().apply(configure).build()
