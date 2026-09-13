package org.mobilenativefoundation.store6.testing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Programmable [Store] for deterministic tests: scripted outcomes, recorded interactions, honest
 * result flags, and one failure channel. [Freshness] values are recorded but never interpreted;
 * compose the seam fakes into a real `store {}` when testing engine policy.
 *
 * Per-key histories are append-only and unbounded for the lifetime of this test-scoped fake. Every
 * history frame is followed by [yield], deliberately providing stronger delivery than the engine
 * so StateFlow/stateIn consumers can assert each lifecycle frame without conflation.
 *
 * A stream is cold and records its interaction when collection starts. A resident key emits an
 * immediate Data snapshot without Loading. An absent key emits Loading and consumes at most one
 * queued outcome, then remains live. Stream errors are values, never thrown. A fresh [get] returns
 * residence without consuming a script; an absent get consumes one outcome, returning a scripted
 * value or throwing a [StoreException] through the public result-factory door. A resident
 * Revalidated outcome refreshes its write time and clears staleness; without residence it becomes
 * Missing on both channels.
 *
 * Invalidation is a stale-mark only and never consumes a script. A stale Data frame reports
 * `refreshing` from whether a script is currently pending. Scripted staleness is consumed at the
 * next demand from an active collector, a later stream after its stale snapshot, or behind a
 * stale [get] read. One CAS consumption site allows at most one winner across concurrent
 * collectors. With no demand, the queued outcome is retained; with no queued outcome, the stale
 * frame has `refreshing=false` and no synthetic error. There is no invalidate divergence from the
 * engine's demand-deferred posture.
 *
 * Clear drops residence, emits Loading to active collectors, and retains the script for later
 * demand. Namespace and global operations sweep resident keys. Durable watermarks are deliberately
 * absent here; compose [FakeBookkeeper] into a real store when testing that engine policy.
 *
 * Data age is derived from [wallClock] and clamped at zero. [setValue] defaults to
 * [Origin.MEMORY], scripted values commit with [Origin.FETCHER], and all stale/refreshing flags are
 * computed from current fake state rather than asserted optimistically.
 *
 * `runtime()` returns null by design. [FakeStore] produces no KeyEvents and performs no overlay
 * projection; runtime, events, overlays, and freshness-policy behavior belong to a real store.
 *
 * [close] is synchronous and idempotent. Active collectors are cancelled; later [Store] operations
 * fail immediately, and stream checks closure both when called and when collection starts. Those
 * exception types, post-close behavior, and the exact message text are pinned against the engine's
 * close lifecycle.
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class FakeStore<K : StoreKey, V : Any>(
    /** The clock every write time and age is measured against; advance it to age values. */
    public val wallClock: TestWallClock = TestWallClock(),
) : Store<K, V> {
    private data class Cell<V : Any>(
        val value: V?,
        val origin: Origin,
        val writtenAtEpochMillis: Long,
        val isStale: Boolean,
        val script: List<Scripted<V>>,
        val history: List<HistoryFrame<V>>,
    )

    private class HistoryFrame<V : Any>(
        val result: StoreResult<V>,
        val staleDemandHead: Scripted<V>? = null,
    )

    private sealed class Scripted<out V : Any> {
        class Value<V : Any>(val value: V) : Scripted<V>()

        class Failure(
            val error: StoreError,
            val servedStale: Boolean,
        ) : Scripted<Nothing>()

        class Revalidated(val age: Duration) : Scripted<Nothing>()
    }

    private val cells = MutableStateFlow<Map<Pair<String, String>, Cell<V>>>(emptyMap())
    private val closed = MutableStateFlow(false)
    private val recorded = MutableStateFlow<List<FakeStoreInteraction>>(emptyList())

    /** Every recorded [Store] call, in call order, since construction or the last clear. */
    public val interactions: List<FakeStoreInteraction>
        get() = recorded.value

    /** Empties [interactions]; recording continues for later calls. */
    public fun clearInteractions() {
        recorded.value = emptyList()
    }

    /**
     * Seeds [key] with a resident [value] without consuming any scripted outcome.
     *
     * The write time is read from [wallClock], so [StoreResult.Data.age] is measured from the
     * clock's current reading. [origin] and [isStale] are recorded verbatim on the seeded value.
     * Active collectors of [key] receive the resulting Data frame.
     */
    public fun setValue(
        key: K,
        value: V,
        origin: Origin = Origin.MEMORY,
        isStale: Boolean = false,
    ) {
        val id = idOf(key)
        cells.update { map ->
            val cell = map[id] ?: emptyCell()
            val now = wallClock.nowEpochMillis()
            map +
                (
                    id to
                        cell.copy(
                            value = value,
                            origin = origin,
                            writtenAtEpochMillis = now,
                            isStale = isStale,
                            history =
                                cell.history +
                                    HistoryFrame(
                                        dataOf(
                                            value,
                                            origin,
                                            now,
                                            isStale,
                                            refreshing = false,
                                        ),
                                    ),
                        )
                )
        }
    }

    /**
     * Appends a scripted successful fetch to [key]'s FIFO queue.
     *
     * When demand consumes it the cell commits [value] with [Origin.FETCHER] at the current
     * [wallClock] reading and clears staleness. Enqueueing alone emits nothing.
     */
    public fun enqueueFetchValue(key: K, value: V) {
        enqueue(key, Scripted.Value(value))
    }

    /**
     * Appends a scripted fetch failure to [key]'s FIFO queue.
     *
     * When demand consumes it, [error] reaches a stream collector as a [StoreResult.Error] value
     * carrying [servedStale]. A [get] with no resident value throws [error] as a [StoreException];
     * a [get] that has residence returns the resident value instead. Either way the resident value
     * and its staleness are left as they were.
     */
    public fun enqueueFetchError(
        key: K,
        error: StoreError,
        servedStale: Boolean = false,
    ) {
        enqueue(key, Scripted.Failure(error, servedStale))
    }

    /**
     * Appends a scripted not-modified confirmation to [key]'s FIFO queue.
     *
     * Consuming it against a resident value refreshes the write time, clears staleness, and emits
     * [StoreResult.Revalidated] carrying [age]. Consuming it with no resident value is an error on
     * both channels instead ([StoreError.Missing]), since there is nothing to confirm.
     */
    public fun enqueueFetchRevalidated(
        key: K,
        age: Duration = Duration.ZERO,
    ) {
        enqueue(key, Scripted.Revalidated(age))
    }

    override fun stream(key: K, freshness: Freshness): Flow<StoreResult<V>> {
        ensureOpen()
        return flow {
            ensureOpen()
            record(FakeStoreInteraction.Stream(key, freshness))
            val id = idOf(key)
            val before = cells.value[id]
            val initialHead = before?.script?.firstOrNull()
            var cursor = before?.history?.size ?: 0
            if (before?.value != null) {
                emit(
                    dataOf(
                        before.value,
                        before.origin,
                        before.writtenAtEpochMillis,
                        before.isStale,
                        refreshing = before.isStale && before.script.isNotEmpty(),
                    ),
                )
                // Initial snapshots are dispatch-pinned too: a stale late-collector snapshot must
                // reach StateFlow/stateIn consumers before its demand commits the scripted result.
                yield()
                if (before.isStale && initialHead != null) {
                    consumeIfStale(key, initialHead)
                }
            } else {
                emit(TestStoreResults.loading())
                yield()
                if (initialHead != null) consumeIfAbsent(key, initialHead)
            }
            combine(cells, closed) { map, isClosed -> map[id] to isClosed }
                .collect { (cell, isClosed) ->
                    if (isClosed) throw CancellationException(CLOSED_MESSAGE)
                    val history = cell?.history.orEmpty()
                    while (cursor < history.size) {
                        val frame = history[cursor]
                        emit(frame.result)
                        cursor += 1
                        // Per-frame dispatch pin: deliberately stronger test delivery prevents
                        // StateFlow/stateIn consumers from losing intermediate lifecycle frames.
                        yield()
                        // The stale frame carries the script head it advertised. Every collector
                        // races for that same head, so only one can win and a failure update cannot
                        // drain the next queued outcome without another demand.
                        frame.staleDemandHead?.let { consumeIfStale(key, it) }
                    }
                }
        }
    }

    override suspend fun get(key: K, freshness: Freshness): V {
        ensureOpen()
        record(FakeStoreInteraction.Get(key, freshness))
        val cell = cells.value[idOf(key)]
        val resident = cell?.value
        if (resident != null) {
            // Stale-while-revalidate mirror: return stale residence and commit one queued outcome
            // behind this read. The next read observes the committed refresh.
            val head = cell.script.firstOrNull()
            if (cell.isStale && head != null) consumeIfStale(key, head)
            return resident
        }
        val head = cell?.script?.firstOrNull()
        return when (val consumed = head?.let { consumeIfAbsent(key, it) }) {
            is Scripted.Value -> consumed.value
            is Scripted.Failure -> throw TestStoreResults.exception(consumed.error)
            is Scripted.Revalidated ->
                throw missingException(
                    key,
                    "a scripted Revalidated confirmed freshness but no resident value exists",
                )
            null ->
                cells.value[idOf(key)]?.value
                    ?: throw missingException(key, "no resident value and no scripted fetch exist")
        }
    }

    override suspend fun invalidate(key: K) {
        ensureOpen()
        record(FakeStoreInteraction.Invalidate(key))
        invalidateCell(idOf(key))
    }

    override suspend fun invalidateNamespace(namespace: StoreNamespace) {
        ensureOpen()
        record(FakeStoreInteraction.InvalidateNamespace(namespace))
        cells.value.keys.filter { it.first == namespace.value }.forEach(::invalidateCell)
    }

    override suspend fun invalidateAll() {
        ensureOpen()
        record(FakeStoreInteraction.InvalidateAll)
        cells.value.keys.forEach(::invalidateCell)
    }

    override suspend fun clear(key: K) {
        ensureOpen()
        record(FakeStoreInteraction.Clear(key))
        clearCell(idOf(key))
    }

    override suspend fun clearNamespace(namespace: StoreNamespace) {
        ensureOpen()
        record(FakeStoreInteraction.ClearNamespace(namespace))
        cells.value.keys.filter { it.first == namespace.value }.forEach(::clearCell)
    }

    override suspend fun clearAll() {
        ensureOpen()
        record(FakeStoreInteraction.ClearAll)
        cells.value.keys.forEach(::clearCell)
    }

    /** Closes this fake synchronously and idempotently. */
    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        record(FakeStoreInteraction.Close)
    }

    private fun idOf(key: K): Pair<String, String> =
        key.namespace.value to key.canonicalId()

    private fun emptyCell(): Cell<V> =
        Cell(
            value = null,
            origin = Origin.FETCHER,
            writtenAtEpochMillis = 0L,
            isStale = false,
            script = emptyList(),
            history = emptyList(),
        )

    private fun record(interaction: FakeStoreInteraction) {
        recorded.update { it + interaction }
    }

    private fun ensureOpen() {
        if (closed.value) throw IllegalStateException(CLOSED_MESSAGE)
    }

    private fun ageOf(writtenAtEpochMillis: Long): Duration =
        (wallClock.nowEpochMillis() - writtenAtEpochMillis).coerceAtLeast(0L).milliseconds

    private fun dataOf(
        value: V,
        origin: Origin,
        writtenAt: Long,
        isStale: Boolean,
        refreshing: Boolean,
    ): StoreResult.Data<V> =
        TestStoreResults.data(value, origin, ageOf(writtenAt), isStale, refreshing)

    private fun missingException(key: K, reason: String): StoreException =
        TestStoreResults.exception(
            TestStoreResults.missing(
                key,
                "FakeStore.get could not return a value for key " +
                    "${key.namespace.value}/${key.canonicalId()}: $reason. Seed setValue() or " +
                    "script enqueueFetchValue()/enqueueFetchError() before reading.",
            ),
        )

    private fun enqueue(key: K, scripted: Scripted<V>) {
        val id = idOf(key)
        cells.update { map ->
            val cell = map[id] ?: emptyCell()
            map + (id to cell.copy(script = cell.script + scripted))
        }
    }

    /**
     * Consumes the script head when no resident value exists; at most one consumer wins (CAS).
     * Owns the absent-Revalidated case because it holds the real key for the Missing payload.
     */
    private fun consumeIfAbsent(key: K, expectedHead: Scripted<V>): Scripted<V>? {
        val id = idOf(key)
        while (true) {
            val current = cells.value
            val cell = current[id]
            if (cell?.value != null) return null
            val head = cell?.script?.firstOrNull() ?: return null
            if (head !== expectedHead) return null
            val next =
                when (head) {
                    is Scripted.Revalidated ->
                        cell.copy(
                            script = cell.script.drop(1),
                            history =
                                cell.history +
                                    HistoryFrame(
                                        TestStoreResults.error(
                                            TestStoreResults.missing(
                                                key,
                                                "a scripted Revalidated arrived with no resident " +
                                                    "value for ${key.namespace.value}/" +
                                                    "${key.canonicalId()}. Script " +
                                                    "enqueueFetchValue() first or seed setValue().",
                                            ),
                                            servedStale = false,
                                        ),
                                    ),
                        )
                    else -> applyScript(cell, head)
                }
            if (cells.compareAndSet(current, current + (id to next))) return head
        }
    }

    /**
     * Consumes the script head against a stale resident. This is the single demand-driven CAS
     * consumption site for invalidated cells, called from stale [get] demand and from the stream
     * collector loop for active or later stream demand.
     */
    private fun consumeIfStale(key: K, expectedHead: Scripted<V>) {
        val id = idOf(key)
        while (true) {
            val current = cells.value
            val cell = current[id] ?: return
            if (cell.value == null || !cell.isStale) return
            val head = cell.script.firstOrNull() ?: return
            if (head !== expectedHead) return
            if (cells.compareAndSet(current, current + (id to applyScript(cell, head)))) return
        }
    }

    /**
     * Applies one scripted outcome. Revalidated requires residence here; [consumeIfAbsent] owns
     * the absent case because it holds the key used for the Missing payload.
     */
    private fun applyScript(cell: Cell<V>, head: Scripted<V>): Cell<V> {
        val now = wallClock.nowEpochMillis()
        val rest = cell.script.drop(1)
        return when (head) {
            is Scripted.Value ->
                cell.copy(
                    value = head.value,
                    origin = Origin.FETCHER,
                    writtenAtEpochMillis = now,
                    isStale = false,
                    script = rest,
                    history =
                        cell.history +
                            HistoryFrame(
                                dataOf(
                                    head.value,
                                    Origin.FETCHER,
                                    now,
                                    isStale = false,
                                    refreshing = false,
                                ),
                            ),
                )
            is Scripted.Failure ->
                cell.copy(
                    script = rest,
                    history =
                        cell.history +
                            HistoryFrame(TestStoreResults.error(head.error, head.servedStale)),
                )
            is Scripted.Revalidated -> {
                check(cell.value != null) {
                    "applyScript requires a resident value for Revalidated; " +
                        "consumeIfAbsent owns the absent case."
                }
                cell.copy(
                    writtenAtEpochMillis = now,
                    isStale = false,
                    script = rest,
                    history = cell.history + HistoryFrame(TestStoreResults.revalidated(head.age)),
                )
            }
        }
    }

    /**
     * Invalidate is a stale-mark only, the engine's epoch-bump analog. It appends the stale
     * re-emission frame for active collectors and never consumes a script; stale get, active
     * stream, or later stream demand owns consumption.
     */
    private fun invalidateCell(id: Pair<String, String>) {
        cells.update { map ->
            val cell = map[id] ?: return@update map
            if (cell.value == null) return@update map
            val refreshing = cell.script.isNotEmpty()
            val demandHead = cell.script.firstOrNull()
            map +
                (
                    id to
                        cell.copy(
                            isStale = true,
                            history =
                                cell.history +
                                    HistoryFrame(
                                        result =
                                            dataOf(
                                                cell.value,
                                                cell.origin,
                                                cell.writtenAtEpochMillis,
                                                isStale = true,
                                                refreshing = refreshing,
                                            ),
                                        staleDemandHead = demandHead,
                                    ),
                        )
                )
        }
    }

    private fun clearCell(id: Pair<String, String>) {
        cells.update { map ->
            val cell = map[id] ?: return@update map
            if (cell.value == null) return@update map
            map +
                (
                    id to
                        cell.copy(
                            value = null,
                            isStale = false,
                            history = cell.history + HistoryFrame(TestStoreResults.loading()),
                        )
                )
        }
    }

    private companion object {
        // Core keeps STORE_CLOSED_MESSAGE internal by design (diagnostics are review-gated text,
        // not ABI). This literal is pinned by the close conformance tests here and by
        // StoreCloseLifecycleTest in core; change both pins together if the message ever
        // changes.
        private const val CLOSED_MESSAGE = "Store is closed."
    }
}
