@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.paging.sample

import androidx.paging.ItemSnapshotList
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.asItemSnapshotListFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.mutations.MutationAck
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationKeyIdentity
import org.mobilenativefoundation.store6.mutations.MutationKeyResolver
import org.mobilenativefoundation.store6.mutations.MutationPresentAck
import org.mobilenativefoundation.store6.mutations.MutationPush
import org.mobilenativefoundation.store6.mutations.MutationRetirement
import org.mobilenativefoundation.store6.mutations.MutationRetirementAck
import org.mobilenativefoundation.store6.mutations.MutationServer
import org.mobilenativefoundation.store6.mutations.MutationStore
import org.mobilenativefoundation.store6.mutations.MutatorRef
import org.mobilenativefoundation.store6.mutations.StaleSet
import org.mobilenativefoundation.store6.mutations.mutationStore
import org.mobilenativefoundation.store6.mutations.mutatorRegistry
import org.mobilenativefoundation.store6.paging.pagingSourceFactory
import kotlin.time.Duration.Companion.days

public fun main(): Unit =
    runBlocking {
        withTimeout(SAMPLE_TIMEOUT_MILLIS) {
            runSample()
        }
    }

private suspend fun CoroutineScope.runSample() {
    val page0Key = PageKey(index = 0, limit = PAGE_SIZE)
    val page1Key = PageKey(index = 1, limit = PAGE_SIZE)
    val page2Key = PageKey(index = 2, limit = PAGE_SIZE)
    val initialPage0 = page("page-0-v1", next = 1)
    val initialPage1 = page("page-1-v1", next = 2)
    val regeneratedPage0 = page("page-0-v2", next = 1)
    val namespacePage0 = page("page-0-v3", next = 1)
    val namespacePage1 = page("page-1-v2", next = 2)
    val olderPage2 = page("page-2-old", next = null)
    val newerPage2 = page("page-2-new", next = null)
    val sourceOfTruth = SampleSourceOfTruth()
    val bookkeeper = SampleBookkeeper()
    val backend = PageBackend().apply {
        seed(page0Key, initialPage0)
        seed(page1Key, initialPage1)
        seed(page2Key, newerPage2)
    }
    val store =
        store<PageKey, Page> {
            fetcher(backend)
            persistence(sourceOfTruth)
            bookkeeper(bookkeeper)
        }
    val mutationBackend = MutationPageBackend()
    val pageMutations = PageMutations()
    val mutationStore = mutationStore(pageMutations, mutationBackend)
    val snapshotJob = SupervisorJob(coroutineContext[Job])
    val snapshotScope = CoroutineScope(coroutineContext + snapshotJob)

    try {
        val pager = store.pager(appendFreshness = Freshness.MaxAge(1.days))
        val snapshots = pager.snapshots(snapshotScope)

        val coldPage0 = snapshots.awaitItems(initialPage0.items)
        pager.append()
        val coldWindow = snapshots.awaitItems(initialPage0.items + initialPage1.items)
        check(coldPage0 == initialPage0.items)
        check(coldWindow == initialPage0.items + initialPage1.items)
        check(backend.totalFetchCount() == 2L)
        println("Scene 1: cold page 0 + append page 1; items=$coldWindow; fetches=2")

        backend.seed(page0Key, regeneratedPage0)
        val page0FetchesBeforeInvalidation = backend.fetchCount(page0Key)
        store.invalidate(page0Key)
        val regeneratedWindow = snapshots.awaitFirstPage(regeneratedPage0.items)
        check(backend.fetchCount(page0Key) == page0FetchesBeforeInvalidation + 1L)
        check(backend.totalFetchCount() == 3L)
        println(
            "Scene 2: invalidate page 0 -> regenerated; " +
                "items=${regeneratedWindow.take(PAGE_SIZE)}; fetches=${backend.totalFetchCount()}",
        )

        sourceOfTruth.write(page2Key, olderPage2)
        bookkeeper.recordSuccess(page2Key, SampleStoreMeta(System.currentTimeMillis()))
        check(backend.fetchCount(page2Key) == 0L)
        backend.seed(page0Key, namespacePage0)
        backend.seed(page1Key, namespacePage1)
        store.invalidateNamespace(PAGES_NAMESPACE)
        pager.refresh()
        snapshots.awaitFirstPage(namespacePage0.items)
        pager.append()
        snapshots.awaitItems(namespacePage0.items + namespacePage1.items)

        var olderPage2Observed = false
        val page2Probe =
            snapshotScope.launch(start = CoroutineStart.UNDISPATCHED) {
                snapshots.collect { snapshot ->
                    if (snapshot.items.any { item -> item.startsWith("page-2-old-") }) {
                        olderPage2Observed = true
                    }
                }
            }
        pager.append()
        val namespaceWindow =
            snapshots.awaitItems(namespacePage0.items + namespacePage1.items + newerPage2.items)
        page2Probe.cancelAndJoin()
        check(!olderPage2Observed)
        check(backend.fetchCount(page2Key) == 1L)
        println(
            "Scene 3: namespace watermark refreshed never-fetched page 2; " +
                "page0=${namespaceWindow.take(PAGE_SIZE)}; " +
                "page2=${namespaceWindow.takeLast(PAGE_SIZE)}; page2-fetches=1",
        )

        val mutationKey = PageKey(index = 0, limit = PAGE_SIZE)
        val original = page("mutation-original", next = null)
        val optimistic = page("mutation-optimistic", next = null)
        val authoritative = page("mutation-authoritative", next = null)
        mutationBackend.seed(mutationKey, original)
        mutationBackend.acknowledge(mutationKey, authoritative)
        val mutationPager = mutationStore.pager(appendFreshness = Freshness.CachedOrFetch)
        val mutationSnapshots = mutationPager.snapshots(snapshotScope)
        check(mutationSnapshots.awaitItems(original.items) == original.items)
        check(mutationBackend.fetchCount(mutationKey) == 1L)

        val probe = OriginProbe(original, optimistic, authoritative)
        val probeJob =
            snapshotScope.launch(start = CoroutineStart.UNDISPATCHED) {
                probe.collect(mutationStore, mutationKey)
            }
        probe.initial.await()
        mutationStore.mutate(mutationKey, pageMutations.replacePage, optimistic)
        val overlay = probe.overlay.await()
        check(overlay.origin == Origin.OVERLAY)
        check(mutationSnapshots.awaitItems(optimistic.items) == optimistic.items)
        val fetchesBeforeAcknowledgement = mutationBackend.fetchCount(mutationKey)

        mutationStore.drain(mutationKey)
        val adopted = probe.adopted.await()
        check(adopted.origin == Origin.SOT)
        check(mutationSnapshots.awaitItems(authoritative.items) == authoritative.items)
        check(mutationStore.pending(mutationKey).isEmpty())
        val acknowledgementFetches =
            mutationBackend.fetchCount(mutationKey) - fetchesBeforeAcknowledgement
        check(acknowledgementFetches == 0L)
        probeJob.cancelAndJoin()
        println(
            "Scene 4: OVERLAY frame observed; SOT adoption observed; " +
                "ack-path fetches=$acknowledgementFetches",
        )
    } finally {
        snapshotJob.cancelAndJoin()
        mutationStore.close()
        store.close()
    }
}

private fun Store<PageKey, Page>.pager(appendFreshness: Freshness): Pager<Int, String> =
    Pager(
        PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = PAGE_SIZE,
            enablePlaceholders = false,
        ),
        pagingSourceFactory =
            pagingSourceFactory {
                pageKey { paginationKey, loadSize ->
                    PageKey(index = paginationKey ?: 0, limit = loadSize)
                }
                items { value -> value.items }
                nextKey { _, value -> value.next }
                prevKey { _, value -> value.prev }
                freshness { loadType ->
                    when (loadType) {
                        LoadType.APPEND -> appendFreshness
                        LoadType.PREPEND,
                        LoadType.REFRESH,
                        -> Freshness.CachedOrFetch
                    }
                }
            },
    )

private fun Pager<Int, String>.snapshots(
    scope: CoroutineScope,
): SharedFlow<ItemSnapshotList<String>> =
    flow
        .asItemSnapshotListFlow()
        .shareIn(scope, started = SharingStarted.Eagerly, replay = 1)

private suspend fun SharedFlow<ItemSnapshotList<String>>.awaitItems(
    expected: List<String>,
): List<String> = first { snapshot -> snapshot.items == expected }.items

private suspend fun SharedFlow<ItemSnapshotList<String>>.awaitFirstPage(
    expected: List<String>,
): List<String> = first { snapshot -> snapshot.items.take(PAGE_SIZE) == expected }.items

private data class PageKey(
    val index: Int,
    val limit: Int,
) : StoreKey {
    override val namespace: StoreNamespace = PAGES_NAMESPACE

    override fun canonicalId(): String = "$index+$limit"
}

private data class Page(
    val items: List<String>,
    val next: Int?,
    val prev: Int?,
)

private fun page(
    prefix: String,
    next: Int?,
    prev: Int? = null,
): Page = Page(List(PAGE_SIZE) { index -> "$prefix-$index" }, next = next, prev = prev)

private open class PageBackend : Fetcher<PageKey, Page> {
    private val truth = ConcurrentHashMap<PageKey, Page>()
    private val fetchCounts = ConcurrentHashMap<PageKey, AtomicLong>()

    fun seed(
        key: PageKey,
        value: Page,
    ) {
        truth[key] = value
    }

    fun fetchCount(key: PageKey): Long = fetchCounts[key]?.get() ?: 0L

    fun totalFetchCount(): Long = fetchCounts.values.sumOf { count -> count.get() }

    override suspend fun fetch(
        key: PageKey,
        etag: String?,
    ): FetcherResult<Page> {
        fetchCounts.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        return FetcherResult.Success(
            checkNotNull(truth[key]) {
                "No backend page for ${key.namespace.value}/${key.canonicalId()}."
            },
        )
    }
}

private class MutationPageBackend : PageBackend(), MutationServer<PageKey, Page> {
    private val acknowledgements = ConcurrentHashMap<PageKey, Page>()

    fun acknowledge(
        key: PageKey,
        value: Page,
    ) {
        acknowledgements[key] = value
    }

    override suspend fun push(request: MutationPush<PageKey, Page>): MutationAck<PageKey, Page> {
        val authoritative =
            checkNotNull(acknowledgements[request.key]) {
                "No acknowledgement for ${request.identity.namespace}/${request.identity.canonicalId}."
            }
        seed(request.key, authoritative)
        return MutationPresentAck(
            authoritative = authoritative,
            etag = "sample-authoritative",
            canonicalKey = null,
        )
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(confirmedThroughSequence = request.retiredThroughSequence)
}

private class PageMutations {
    lateinit var replacePage: MutatorRef<PageKey, Page, Page>
        private set

    val registry =
        mutatorRegistry<PageKey, Page> {
            replacePage =
                update(
                    id = "replace-page",
                    version = 1,
                    codec = PageCodec,
                    stales = { _, _ ->
                        StaleSet(keys = emptySet(), namespaces = emptySet())
                    },
                    project = { _, replacement -> replacement },
                )
        }
}

private fun mutationStore(
    mutations: PageMutations,
    backend: MutationPageBackend,
): MutationStore<PageKey, Page> =
    mutationStore(
        registry = mutations.registry,
        server = backend,
        keyResolver = PageKeyResolver,
        valueCodecVersion = 1,
        valueCodec = PageCodec,
    ) {
        fetcher(backend)
    }

private object PageKeyResolver : MutationKeyResolver<PageKey> {
    override suspend fun resolve(identity: MutationKeyIdentity): PageKey? {
        if (identity.namespace != PAGES_NAMESPACE.value) return null
        val fields = identity.canonicalId.split('+')
        if (fields.size != 2) return null
        return PageKey(
            index = fields[0].toIntOrNull() ?: return null,
            limit = fields[1].toIntOrNull() ?: return null,
        )
    }
}

private object PageCodec : MutationCodec<Page> {
    override fun encode(value: Page): ByteArray =
        buildList {
            add(value.next?.toString().orEmpty())
            add(value.prev?.toString().orEmpty())
            add(value.items.size.toString())
            addAll(value.items)
        }.joinToString(CODEC_SEPARATOR).encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): Page {
        require(version == 1) { "Sample Page values require version 1; was $version." }
        val fields = bytes.decodeToString().split(CODEC_SEPARATOR)
        require(fields.size >= 3) { "Sample Page values require three header fields." }
        val itemCount = fields[2].toInt()
        require(fields.size == itemCount + 3) {
            "Sample Page declared $itemCount items but encoded ${fields.size - 3}."
        }
        return Page(
            items = fields.drop(3),
            next = fields[0].ifEmpty { null }?.toInt(),
            prev = fields[1].ifEmpty { null }?.toInt(),
        )
    }
}

private class OriginProbe(
    private val original: Page,
    private val optimistic: Page,
    private val authoritative: Page,
) {
    val initial = CompletableDeferred<StoreResult.Data<Page>>()
    val overlay = CompletableDeferred<StoreResult.Data<Page>>()
    val adopted = CompletableDeferred<StoreResult.Data<Page>>()

    suspend fun collect(
        store: Store<PageKey, Page>,
        key: PageKey,
    ) {
        store.stream(key, Freshness.LocalOnly)
            .filterIsInstance<StoreResult.Data<Page>>()
            .collect { data ->
                when {
                    data.value == original -> initial.complete(data)
                    data.value == optimistic && data.origin == Origin.OVERLAY ->
                        overlay.complete(data)
                    overlay.isCompleted &&
                        data.value == authoritative &&
                        data.origin == Origin.SOT -> adopted.complete(data)
                }
            }
    }
}

private class SampleSourceOfTruth : SourceOfTruth<PageKey, Page> {
    private data class VersionedRow(
        val sequence: Long,
        val value: Page?,
    )

    private val sequence = AtomicLong()
    private val rows = ConcurrentHashMap<PageKey, MutableStateFlow<VersionedRow>>()

    override fun reader(key: PageKey): Flow<Page?> = row(key).map { versioned -> versioned.value }

    override suspend fun write(
        key: PageKey,
        value: Page,
    ) {
        row(key).value = VersionedRow(sequence.incrementAndGet(), value)
    }

    override suspend fun delete(key: PageKey) {
        row(key).value = VersionedRow(sequence.incrementAndGet(), null)
    }

    override suspend fun deleteNamespace(namespace: StoreNamespace) {
        rows.forEach { (key, row) ->
            if (key.namespace.value == namespace.value) {
                row.value = VersionedRow(sequence.incrementAndGet(), null)
            }
        }
    }

    override suspend fun deleteAll() {
        rows.values.forEach { row ->
            row.value = VersionedRow(sequence.incrementAndGet(), null)
        }
    }

    private fun row(key: PageKey): MutableStateFlow<VersionedRow> =
        rows.computeIfAbsent(key) { MutableStateFlow(VersionedRow(0L, null)) }
}

private class SampleBookkeeper : Bookkeeper {
    private data class Identity(
        val namespace: String,
        val canonicalId: String,
    )

    private data class Record(
        val meta: StoreMeta?,
        val successSequence: Long?,
        val lastFailureAtEpochMillis: Long?,
        val consecutiveFailures: Int,
        val staleSequence: Long?,
    )

    private val lock = Mutex()
    private var sequence = 0L
    private val records = mutableMapOf<Identity, Record>()
    private val namespaceWatermarks = mutableMapOf<String, Long>()
    private var globalWatermark = 0L

    override suspend fun recordSuccess(
        key: StoreKey,
        meta: StoreMeta,
    ) {
        val identity = key.identity()
        lock.withLock {
            val previous = records[identity]
            records[identity] =
                Record(
                    meta = meta,
                    successSequence = nextSequence(),
                    lastFailureAtEpochMillis = null,
                    consecutiveFailures = 0,
                    staleSequence = previous?.staleSequence,
                )
        }
    }

    override suspend fun recordFailure(
        key: StoreKey,
        atEpochMillis: Long,
    ) {
        val identity = key.identity()
        lock.withLock {
            val previous = records[identity]
            records[identity] =
                Record(
                    meta = previous?.meta,
                    successSequence = previous?.successSequence,
                    lastFailureAtEpochMillis = atEpochMillis,
                    consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                    staleSequence = previous?.staleSequence,
                )
        }
    }

    override suspend fun status(key: StoreKey): KeyStatus? {
        val identity = key.identity()
        return lock.withLock {
            val record = records[identity]
            val staleSequence =
                maxOf(
                    record?.staleSequence ?: 0L,
                    namespaceWatermarks[identity.namespace] ?: 0L,
                    globalWatermark,
                )
            if (record == null && staleSequence == 0L) {
                null
            } else {
                KeyStatus(
                    meta = record?.meta,
                    lastSuccessSequence = record?.successSequence,
                    lastFailureAtEpochMillis = record?.lastFailureAtEpochMillis,
                    consecutiveFailures = record?.consecutiveFailures ?: 0,
                    durablyStale = staleSequence > (record?.successSequence ?: 0L),
                )
            }
        }
    }

    override suspend fun forget(key: StoreKey) {
        lock.withLock {
            records.remove(key.identity())
        }
    }

    override suspend fun markStale(key: StoreKey) {
        val identity = key.identity()
        lock.withLock {
            val previous = records[identity]
            records[identity] =
                Record(
                    meta = previous?.meta,
                    successSequence = previous?.successSequence,
                    lastFailureAtEpochMillis = previous?.lastFailureAtEpochMillis,
                    consecutiveFailures = previous?.consecutiveFailures ?: 0,
                    staleSequence = nextSequence(),
                )
        }
    }

    override suspend fun advanceStaleWatermark(namespace: StoreNamespace) {
        lock.withLock {
            namespaceWatermarks[namespace.value] = nextSequence()
        }
    }

    override suspend fun advanceGlobalStaleWatermark() {
        lock.withLock {
            globalWatermark = nextSequence()
        }
    }

    override suspend fun forgetNamespace(namespace: StoreNamespace) {
        lock.withLock {
            records.keys.removeAll { identity -> identity.namespace == namespace.value }
        }
    }

    override suspend fun forgetAll() {
        lock.withLock {
            records.clear()
        }
    }

    private fun StoreKey.identity(): Identity = Identity(namespace.value, canonicalId())

    private fun nextSequence(): Long {
        check(sequence < Long.MAX_VALUE) { "Sample bookkeeper sequence exhausted." }
        sequence += 1L
        return sequence
    }
}

private class SampleStoreMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String? = null,
) : StoreMeta

private const val PAGE_SIZE = 3
private const val SAMPLE_TIMEOUT_MILLIS = 20_000L
private const val CODEC_SEPARATOR = "\u0000"
private val PAGES_NAMESPACE = StoreNamespace("sample-pages")
