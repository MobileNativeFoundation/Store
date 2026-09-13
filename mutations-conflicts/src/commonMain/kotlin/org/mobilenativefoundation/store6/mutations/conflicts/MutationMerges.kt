@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.conflicts

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.mutations.MutationConflictResolution
import org.mobilenativefoundation.store6.mutations.MutationPresence

/** Factories for merge policies accepted by the mutation conflict builder. */
@ExperimentalStoreApi
public object MutationMerges {

    /**
     * Selects server-wins for every combination of base, local, and authoritative presence.
     *
     * This is behaviorally identical to registering no merge. It exists so the choice is explicit
     * in code review, pairs with a caller-supplied `precondition { }`, and names the default.
     */
    @ExperimentalStoreApi
    public fun <V : Any> serverWins(): MutationMergeFunction<V> =
        { _, _, _ -> MutationConflictResolution.ServerWins }

    /**
     * Reasserts the same local presence after every conflict, including `Retry(Absent)` for a
     * deletion.
     *
     * A retry converges when the next generation's precondition — built from the post-barrier
     * recapture — matches server state at the next push. When the barrier fetch adopts the
     * server's conflicting value, that is the common case. When it does not, or the server keeps
     * moving, the unchanged-conflict bound parks the intent after three consecutive conflicted
     * generations with an identical server timestamp/etag pair. A server minting fresh serverMeta
     * on every rejection never trips the bound, so client-wins retries indefinitely at drain
     * cadence against such a server.
     */
    @ExperimentalStoreApi
    public fun <V : Any> clientWins(): MutationMergeFunction<V> =
        { _, mine, _ -> MutationConflictResolution.Retry(mine) }

    /**
     * The mutator's projector must assign a fresh stamp to every local write. A projector copying
     * the base's stamp produces stamps that never exceed the server's, and `mine` must be strictly
     * newer to win, so the policy silently degrades to server-wins.
     *
     * Decision table (P = present, A = absent):
     * - mine P, theirs P: `writtenAt(mine) > writtenAt(theirs)` resolves `Retry(mine)`; otherwise
     *   `ServerWins`. Ties go to the server.
     * - mine A, theirs P: [onMineAbsent] — `THEIRS` resolves `ServerWins`; `MINE` resolves
     *   `Retry(Absent)`, re-pushing the deletion.
     * - mine P, theirs A: [onTheirsAbsent] — `THEIRS` resolves `ServerWins`, staying deleted;
     *   `MINE` resolves `Retry(mine)`, recreating the entity.
     * - mine A, theirs A: `ServerWins`; nothing to contend.
     *
     * `base` never participates. A bare `lastWriteWins { ... }` call is last-write-wins when both
     * sides are present and server-wins otherwise. Wall-clock stamps are skew-sensitive; prefer
     * server-assigned or hybrid stamps. A [writtenAt] function that throws parks the intent with
     * kind `CONFLICT` and detail `"merge-failed"`.
     */
    @ExperimentalStoreApi
    public fun <V : Any> lastWriteWins(
        onMineAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        onTheirsAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        writtenAt: (V) -> Long,
    ): MutationMergeFunction<V> =
        { _, mine, theirs ->
            when (mine) {
                is MutationPresence.Present ->
                    when (theirs) {
                        is MutationPresence.Present ->
                            if (writtenAt(mine.value) > writtenAt(theirs.value)) {
                                MutationConflictResolution.Retry(mine)
                            } else {
                                MutationConflictResolution.ServerWins
                            }

                        MutationPresence.Absent ->
                            when (onTheirsAbsent) {
                                MutationConflictBias.THEIRS ->
                                    MutationConflictResolution.ServerWins

                                MutationConflictBias.MINE ->
                                    MutationConflictResolution.Retry(mine)
                            }
                    }

                MutationPresence.Absent ->
                    when (theirs) {
                        is MutationPresence.Present ->
                            when (onMineAbsent) {
                                MutationConflictBias.THEIRS ->
                                    MutationConflictResolution.ServerWins

                                MutationConflictBias.MINE ->
                                    MutationConflictResolution.Retry(mine)
                            }

                        MutationPresence.Absent -> MutationConflictResolution.ServerWins
                    }
            }
        }

    /**
     * Value-level three-way merge. The presence matrix is pack-owned. The value merge is
     * caller-owned.
     *
     * Decision table (P = present, A = absent; base is any):
     * - mine P, theirs P: `Retry(Present(merge(baseOrNull, mine, theirs)))` where `baseOrNull`
     *   is the base value when base is `Present`, or `null` when base is `Absent` (both sides
     *   created independently).
     * - mine A, theirs P: [onMineAbsent] — `THEIRS` resolves `ServerWins`; `MINE` resolves
     *   `Retry(Absent)`.
     * - mine P, theirs A: [onTheirsAbsent] — `THEIRS` resolves `ServerWins`; `MINE` resolves
     *   `Retry(mine)`.
     * - mine A, theirs A: `ServerWins`; nothing to contend.
     *
     * The pack never converts an affirmative `Retry` into `ServerWins` on value equality. Even
     * when `merge(...) == theirs` the resolution is `Retry`, because a successful push runs the
     * mutator's declared invalidation effects and adoption while `ServerWins` terminally skips
     * them. A [merge] function that throws parks the intent with kind `CONFLICT` and detail
     * `"merge-failed"`.
     */
    @ExperimentalStoreApi
    public fun <V : Any> threeWay(
        onMineAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        onTheirsAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        merge: (base: V?, mine: V, theirs: V) -> V,
    ): MutationMergeFunction<V> =
        { base, mine, theirs ->
            when (mine) {
                is MutationPresence.Present ->
                    when (theirs) {
                        is MutationPresence.Present -> {
                            val baseOrNull =
                                when (base) {
                                    is MutationPresence.Present -> base.value
                                    MutationPresence.Absent -> null
                                }
                            MutationConflictResolution.Retry(
                                MutationPresence.Present(
                                    merge(baseOrNull, mine.value, theirs.value),
                                ),
                            )
                        }

                        MutationPresence.Absent ->
                            when (onTheirsAbsent) {
                                MutationConflictBias.THEIRS ->
                                    MutationConflictResolution.ServerWins

                                MutationConflictBias.MINE ->
                                    MutationConflictResolution.Retry(mine)
                            }
                    }

                MutationPresence.Absent ->
                    when (theirs) {
                        is MutationPresence.Present ->
                            when (onMineAbsent) {
                                MutationConflictBias.THEIRS ->
                                    MutationConflictResolution.ServerWins

                                MutationConflictBias.MINE ->
                                    MutationConflictResolution.Retry(mine)
                            }

                        MutationPresence.Absent -> MutationConflictResolution.ServerWins
                    }
            }
        }

    /**
     * This policy merges the fields you register and resolves every unregistered field to
     * `theirs`. The builder cannot enumerate a type's properties in common Kotlin, so it cannot
     * detect an unregistered locally-mutated field. Forgetting one silently surrenders that
     * field's local edit. Register every field the app mutates locally.
     *
     * Decision table (P = present, A = absent; base is any):
     * - mine P, theirs P: merge registered fields and resolve `Retry(Present(canvas))`.
     * - mine A, theirs P: [onMineAbsent] — `THEIRS` resolves `ServerWins`; `MINE` resolves
     *   `Retry(Absent)`.
     * - mine P, theirs A: [onTheirsAbsent] — `THEIRS` resolves `ServerWins`; `MINE` resolves
     *   `Retry(mine)`.
     * - mine A, theirs A: `ServerWins`; nothing to contend.
     *
     * For each registered field, `m = get(mine)`, `t = get(theirs)`, and `b = get(base)` when
     * base is `Present`:
     * - base P, `m == b`: keep `t`.
     * - base P, `m != b && t == b`: apply `set(canvas, m)`.
     * - base P, `m != b && t != b && m == t`: keep `t`.
     * - base P, `m != b && t != b && m != t`: the field is contested.
     * - base A, `m == t`: keep `t`.
     * - base A, `m != t`: the field is contested.
     * - contested with a field combiner: apply `set(canvas, combine(baseOrNull, m, t))`.
     * - contested without a field combiner: [onBothChanged] — `THEIRS` keeps `t`; `MINE`
     *   applies `set(canvas, m)`.
     *
     * Field values compare with `==` (`Any.equals`). `Array` and `ByteArray` compare by identity,
     * not content, and `Double.NaN != NaN`. Registrations apply in order over one local canvas
     * initialized to `theirs`; the policy makes no additional copy. A later registration's `set`
     * sees earlier results; where overlapping lenses collide, the last registration that writes
     * wins, and a later registration that keeps `theirs` leaves an earlier registration's write in
     * place.
     *
     * A field's `combine` receives the whole base value as `V?`, not the baseline field as `F?`.
     * It receives the base value when base is `Present` and null when base is `Absent`. This
     * distinguishes an absent baseline entity from a present entity whose field is null. The
     * factory runs [configure] on a fresh builder and snapshots the registrations into an
     * immutable list before returning. An empty block throws `IllegalArgumentException`. Calling
     * `field(...)` on an escaped builder after the factory returns throws `IllegalStateException`.
     *
     * `get`, `set`, and `combine` must be pure and deterministic. A throw parks the intent with
     * kind `CONFLICT` and detail `"merge-failed"`.
     */
    @ExperimentalStoreApi
    public fun <V : Any> fields(
        onMineAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        onTheirsAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        onBothChanged: MutationConflictBias = MutationConflictBias.THEIRS,
        configure: MutationFieldMergeBuilder<V>.() -> Unit,
    ): MutationMergeFunction<V> {
        val builder = MutationFieldMergeBuilder<V>()
        builder.configure()
        val registrations = builder.sealAndSnapshot()
        require(registrations.isNotEmpty()) {
            "MutationMerges.fields requires at least one registered field."
        }

        return { base, mine, theirs ->
            when (mine) {
                is MutationPresence.Present ->
                    when (theirs) {
                        is MutationPresence.Present -> {
                            var canvas = theirs.value
                            for (registration in registrations) {
                                canvas =
                                    registration(
                                        base,
                                        mine.value,
                                        theirs.value,
                                        canvas,
                                        onBothChanged,
                                    )
                            }
                            MutationConflictResolution.Retry(
                                MutationPresence.Present(canvas),
                            )
                        }

                        MutationPresence.Absent ->
                            when (onTheirsAbsent) {
                                MutationConflictBias.THEIRS ->
                                    MutationConflictResolution.ServerWins

                                MutationConflictBias.MINE ->
                                    MutationConflictResolution.Retry(mine)
                            }
                    }

                MutationPresence.Absent ->
                    when (theirs) {
                        is MutationPresence.Present ->
                            when (onMineAbsent) {
                                MutationConflictBias.THEIRS ->
                                    MutationConflictResolution.ServerWins

                                MutationConflictBias.MINE ->
                                    MutationConflictResolution.Retry(mine)
                            }

                        MutationPresence.Absent -> MutationConflictResolution.ServerWins
                    }
            }
        }
    }
}
