@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace

/**
 * Explicit value state carried by every mutation base, mine, and adoption boundary.
 *
 * [Present] carries a confirmed or projected value; [Absent] is confirmed or projected
 * non-existence. A projector returning a nullable `MutationPresence<V>?` uses `null` to mean
 * exactly one thing: decline this intent. Deletion is never spelled `null`; it is [Absent].
 * Push, acknowledgement, conflict, attempt, and adoption carriers contain non-null presence,
 * never a nullable `V`.
 *
 * Consumers construct presence directly (upsert arguments, conflict `Retry(presence)`), so both
 * variants have public constructors.
 */
@ExperimentalStoreApi
public sealed interface MutationPresence<out V : Any> {
    /** A value that exists (confirmed) or should exist (projected). */
    @ExperimentalStoreApi
    public class Present<V : Any> public constructor(
        /** The non-null carried value. */
        @ExperimentalStoreApi
        public val value: V,
    ) : MutationPresence<V>

    /**
     * Confirmed or projected non-existence.
     *
     * As a base, [Absent] is an existence precondition: apply only if the entity is still
     * absent. It is never an unconditional write.
     */
    @ExperimentalStoreApi
    public object Absent : MutationPresence<Nothing>
}

/**
 * The durable identity pair that alone selects a backend entity.
 *
 * Durable key identity is exactly `(namespace.value, canonicalId())`. Hashes, object identity,
 * and key class are never durable identity. The library alone constructs identities; a resolver
 * reconstructs the convenience `K` from this exact pair and the engine validates both returned
 * components verbatim.
 */
@ExperimentalStoreApi
public class MutationKeyIdentity internal constructor(
    /** Verbatim [StoreNamespace.value] of the effective key. */
    @ExperimentalStoreApi
    public val namespace: String,

    /** Verbatim [StoreKey.canonicalId] of the effective key. */
    @ExperimentalStoreApi
    public val canonicalId: String,
)

/**
 * Reconstructs a process-local `K` from a durable [MutationKeyIdentity].
 *
 * The resolver is a required factory input: restart-safe drain cannot exist without it. It may
 * perform I/O and may suspend, but it is never invoked while the global journal transaction is
 * held. Returning `null` means the identity cannot be resolved. The engine validates the returned
 * key's namespace and canonical id verbatim against the requested pair; a mismatch is rejected
 * before any transport work. `CancellationException` is always rethrown.
 */
@ExperimentalStoreApi
public fun interface MutationKeyResolver<K : StoreKey> {
    /** Returns the key for [identity], or `null` when the pair cannot be resolved. */
    @ExperimentalStoreApi
    public suspend fun resolve(identity: MutationKeyIdentity): K?
}

/**
 * Versioned byte codec for mutation arguments and store values.
 *
 * Codecs are pure and deterministic for a given version. The library defensively copies every
 * encoded array before durable retention and passes a fresh copy into every [decode], so consumer
 * code can never mutate a stored retry generation. [decode] receives the persisted version, which
 * may be older than the currently registered one; format changes append a version and old
 * decoders remain until the corresponding rows are safely retired and pruned.
 */
@ExperimentalStoreApi
public interface MutationCodec<T : Any> {
    /** Encodes [value] into bytes owned by the caller after return. */
    @ExperimentalStoreApi
    public fun encode(value: T): ByteArray

    /** Decodes [bytes] persisted under [version]; throws when the version or bytes are illegal. */
    @ExperimentalStoreApi
    public fun decode(version: Int, bytes: ByteArray): T
}

/**
 * The library-side defensive-copy boundary for codec output: encoded bytes are copied before
 * retention so a producer holding the returned array cannot mutate stored state.
 */
internal fun <T : Any> MutationCodec<T>.encodeCopied(value: T): ByteArray = encode(value).copyOf()

/**
 * The library-side defensive-copy boundary for codec input: stored bytes are copied before
 * consumer code so a codec retaining its argument cannot mutate a stored retry generation.
 */
internal fun <T : Any> MutationCodec<T>.decodeCopied(version: Int, bytes: ByteArray): T =
    decode(version, bytes.copyOf())

/**
 * The result of a pure registered invalidation function `(key, args) -> StaleSet<K>`.
 *
 * The library copies, normalizes to full identity pairs, deduplicates, and sorts the returned
 * sets before persisting one immutable effect record per key or namespace. The function must be
 * pure: equal inputs must produce structurally equal stale sets.
 */
@ExperimentalStoreApi
public class StaleSet<K : StoreKey> public constructor(
    /** Keys whose confirmed values become stale after this mutation's adoption. */
    @ExperimentalStoreApi
    public val keys: Set<K>,

    /** Namespaces invalidated wholesale after this mutation's adoption. */
    @ExperimentalStoreApi
    public val namespaces: Set<StoreNamespace>,
)

/**
 * The library-owned capture carrier handed to the optional precondition selector.
 *
 * It exists before any transport request: it carries the ordered-capture result and has no
 * selected `baseMeta` and no transport door. The selector receives only this candidate — never a
 * final [MutationPush] — and its result is immediately copied and frozen with the new attempt
 * generation. The selector runs once per newly prepared semantic generation and never on a
 * transport retry.
 */
@ExperimentalStoreApi
public class MutationPreconditionCandidate<K : StoreKey, V : Any> internal constructor(
    /** Alone selects the backend entity and feeds preconditions and idempotency. */
    @ExperimentalStoreApi
    public val identity: MutationKeyIdentity,

    /** Process-local adapter context; fields beyond its validated identity are non-authoritative. */
    @ExperimentalStoreApi
    public val key: K,

    /** The opaque public id of the intent whose generation is being prepared. */
    @ExperimentalStoreApi
    public val mutationId: String,

    /** The generation being prepared; a merge prepares `g + 1`, never edits `g`. */
    @ExperimentalStoreApi
    public val generation: Int,

    /** The ordered-capture confirmed base; [MutationPresence.Absent] is an existence precondition. */
    @ExperimentalStoreApi
    public val base: MutationPresence<V>,

    /** The locally projected outcome for this generation. */
    @ExperimentalStoreApi
    public val mine: MutationPresence<V>,

    /** Library-owned snapshot of the captured metadata, before policy selects the frozen result. */
    @ExperimentalStoreApi
    public val capturedMeta: StoreMeta?,
)

/**
 * The immutable, library-built transport request for one attempt generation.
 *
 * [identity] alone selects the backend entity and feeds preconditions and idempotency. [key] is
 * process-local adapter context; fields beyond its already-validated namespace and canonical id
 * are non-authoritative and may differ across retries. Store6 reconstructs every retry of the
 * same semantic generation from the same defensively copied blobs, so a retry carries the same
 * base, mine, metadata, advertised prefix, and [idempotencyKey]. A merge that changes payload
 * persists generation `g + 1` and a new idempotency key before its first send.
 *
 * Transport cancellation is not failure: once a push has entered `INFLIGHT`, a thrown
 * `CancellationException` is rethrown and leaves that phase intact because remote acceptance is
 * uncertain; the next explicit drain or restart sends this exact same immutable generation.
 * That uncertain generation retains causal authority for its `(clientId, identity.namespace)`:
 * later keys in the same client namespace cannot begin transport until it parks or retires,
 * while keys in different namespaces remain eligible to progress.
 *
 * [baseMeta] strengthens the existence/value precondition when present; `baseMeta == null` never
 * means an unconditional write — [base] itself is always a precondition.
 */
@ExperimentalStoreApi
public class MutationPush<K : StoreKey, V : Any> internal constructor(
    /** Alone selects the backend entity and feeds preconditions and idempotency. */
    @ExperimentalStoreApi
    public val identity: MutationKeyIdentity,

    /** Process-local adapter context; fields beyond its validated identity are non-authoritative. */
    @ExperimentalStoreApi
    public val key: K,

    /** The stable installation/journal identity issuing this push. */
    @ExperimentalStoreApi
    public val clientId: String,

    /** The intent's durable per-client sequence; FIFO and watermark unit. */
    @ExperimentalStoreApi
    public val clientSequence: Long,

    /** The contiguous retired prefix advertised opportunistically for backend receipt cleanup. */
    @ExperimentalStoreApi
    public val retiredThroughSequence: Long,

    /** The opaque public id correlating this push with inspection and events. */
    @ExperimentalStoreApi
    public val mutationId: String,

    /** The semantic attempt generation this push transmits; starts at 1. */
    @ExperimentalStoreApi
    public val generation: Int,

    /** Generation-stable idempotency key; identical across transport retries of one generation. */
    @ExperimentalStoreApi
    public val idempotencyKey: String,

    /** The value-codec version that decodes this generation's base and mine blobs. */
    @ExperimentalStoreApi
    public val valueCodecVersion: Int,

    /** The frozen captured base; always an existence/value precondition. */
    @ExperimentalStoreApi
    public val base: MutationPresence<V>,

    /** The frozen locally projected outcome to apply. */
    @ExperimentalStoreApi
    public val mine: MutationPresence<V>,

    /** The frozen selected precondition metadata; null selects existence/value only. */
    @ExperimentalStoreApi
    public val baseMeta: StoreMeta?,
)

/**
 * The application-owned mutation transport.
 *
 * Implementations own deterministic wire encoding of the library-built carriers. A precondition
 * conflict is signalled only through the callable public core door:
 *
 * ```kotlin
 * throw StoreResults.exception(
 *     StoreResults.conflict(serverMeta, message),
 *     cause,
 * )
 * ```
 */
@ExperimentalStoreApi
public interface MutationServer<K : StoreKey, V : Any> {
    /**
     * Pushes one immutable attempt generation and returns the backend's acknowledgement.
     *
     * Retries of one [MutationPush.idempotencyKey] must be idempotent: a duplicate success must
     * return the same authoritative presence, value, etag, and canonical target. A thrown
     * `CancellationException` is rethrown by the library and leaves the in-flight generation
     * intact for exact replay.
     *
     * Backend coherence obligation for confirmed deletion, certified by returning
     * [MutationAbsentAck]: Every fetch begun after an Absent acknowledgement returns
     * `FetcherResult.Deleted`. Tombstones stop journal replay; they do not mask a backend that
     * violates this contract.
     */
    @ExperimentalStoreApi
    public suspend fun push(request: MutationPush<K, V>): MutationAck<K, V>

    /**
     * Confirms a monotonic retirement checkpoint so the backend can bound idempotency-receipt
     * retention.
     *
     * The returned [MutationRetirementAck.confirmedThroughSequence] must be monotonic and cannot
     * exceed [MutationRetirement.retiredThroughSequence]; the library validates both properties
     * and treats a violation as a protocol failure. The request/ack protocol is idempotent: a
     * later pass may resend the same or a greater prefix. A thrown `CancellationException` is
     * rethrown, leaves the server-confirmed prefix unchanged, and prunes nothing.
     */
    @ExperimentalStoreApi
    public suspend fun retire(request: MutationRetirement): MutationRetirementAck
}

/**
 * The backend's acknowledgement of one pushed generation.
 *
 * The sealed variants make a canonical target on confirmed absence unrepresentable: only
 * [MutationPresentAck] can carry a canonical key. Consumers construct the legal variants; the
 * library alone constructs pushes, retirements, identities, inspection rows, and failures.
 */
@ExperimentalStoreApi
public sealed interface MutationAck<out K : StoreKey, out V : Any> {
    /** Optional backend entity tag recorded by Store freshness bookkeeping. */
    @ExperimentalStoreApi
    public val etag: String?
}

/**
 * A confirmed present outcome, adopted through write-handle apply then fresh confirmation.
 *
 * [canonicalKey] optionally redirects a provisional identity to its same-namespace canonical
 * identity; `null` means the identity is unchanged. A retry of one generation idempotency key
 * must return the same canonical target or the intent parks as a protocol violation.
 */
@ExperimentalStoreApi
public class MutationPresentAck<K : StoreKey, V : Any> public constructor(
    /** The backend-authoritative value written into Store's source of truth. */
    @ExperimentalStoreApi
    public val authoritative: V,

    @ExperimentalStoreApi
    public override val etag: String?,

    /** Optional same-namespace canonical redirect; `null` keeps the pushed identity. */
    @ExperimentalStoreApi
    public val canonicalKey: K?,
) : MutationAck<K, V>

/**
 * A confirmed absent outcome, adopted through the delegated Store's `clear(key)`.
 *
 * Backend coherence obligation: Every fetch begun after an Absent acknowledgement returns
 * `FetcherResult.Deleted`. Tombstones stop journal replay; they do not mask a backend that
 * violates this contract. This variant deliberately has no canonical key, so rekey-on-deletion
 * is unrepresentable in alpha01.
 */
@ExperimentalStoreApi
public class MutationAbsentAck<K : StoreKey, V : Any> public constructor(
    @ExperimentalStoreApi
    public override val etag: String?,
) : MutationAck<K, V>

/**
 * The library-built retirement checkpoint request.
 *
 * A checkpoint confirms only the current contiguous retired prefix; it never advances across
 * parked or active work.
 */
@ExperimentalStoreApi
public class MutationRetirement internal constructor(
    /** The stable installation/journal identity whose prefix is being confirmed. */
    @ExperimentalStoreApi
    public val clientId: String,

    /** The contiguous locally retired prefix offered for confirmation. */
    @ExperimentalStoreApi
    public val retiredThroughSequence: Long,
)

/**
 * The consumer-built confirmation of a retirement checkpoint.
 *
 * Confirmation is monotonic and cannot exceed its request; the library validates both before
 * persisting the server-confirmed prefix, so this carrier stays plain.
 */
@ExperimentalStoreApi
public class MutationRetirementAck public constructor(
    /** The largest client sequence the backend has durably confirmed as retired. */
    @ExperimentalStoreApi
    public val confirmedThroughSequence: Long,
)

/**
 * Library-side retirement-ack validation: the engine calls this when processing a
 * [MutationServer.retire] response, so the public carriers stay plain data.
 *
 * Rejects a confirmation above the requested prefix and any regression below the previously
 * persisted server-confirmed prefix. Returns the validated new confirmed prefix. The engine
 * normalizes the thrown failure as `MutationFailureKind.PROTOCOL`.
 */
internal fun validateRetirementAck(
    request: MutationRetirement,
    ack: MutationRetirementAck,
    previousConfirmedThroughSequence: Long,
): Long {
    require(ack.confirmedThroughSequence <= request.retiredThroughSequence) {
        "Retirement confirmation ${ack.confirmedThroughSequence} exceeds requested prefix " +
            "${request.retiredThroughSequence} for client '${request.clientId}'."
    }
    require(ack.confirmedThroughSequence >= previousConfirmedThroughSequence) {
        "Retirement confirmation ${ack.confirmedThroughSequence} regresses below " +
            "$previousConfirmedThroughSequence for client '${request.clientId}'."
    }
    return ack.confirmedThroughSequence
}

/**
 * Library-side exact-pair resolution validation: the engine calls this on every resolver
 * result before any transport or adoption work.
 *
 * Resolver null and identity mismatch fail with `cause == null`; the original resolver context
 * never enters durable state.
 */
internal fun <K : StoreKey> requireResolvedKey(
    identity: MutationKeyIdentity,
    resolved: K?,
): K {
    checkNotNull(resolved) {
        "MutationKeyResolver returned null for (${identity.namespace}, ${identity.canonicalId})."
    }
    check(
        resolved.namespace.value == identity.namespace &&
            resolved.canonicalId() == identity.canonicalId,
    ) {
        "MutationKeyResolver returned (${resolved.namespace.value}, ${resolved.canonicalId()}) " +
            "for requested identity (${identity.namespace}, ${identity.canonicalId})."
    }
    return resolved
}

/**
 * The explicit outcome of a consumer merge hook after a precondition conflict.
 *
 * [Retry] persists a new generation before transmission; [ServerWins] retires the intent without
 * another push. Without an installed merge, server-wins is the non-removable terminal.
 */
@ExperimentalStoreApi
public sealed interface MutationConflictResolution<out V : Any> {
    /** Retry with a merged outcome; the library persists generation `g + 1` before sending. */
    @ExperimentalStoreApi
    public class Retry<V : Any> public constructor(
        /** The merged outcome to apply; presence, never nullable `V`. */
        @ExperimentalStoreApi
        public val value: MutationPresence<V>,
    ) : MutationConflictResolution<V>

    /** Accept the authoritative server state and retire without another push. */
    @ExperimentalStoreApi
    public object ServerWins : MutationConflictResolution<Nothing>
}
