@file:OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)

package org.mobilenativefoundation.store6.paging

import androidx.paging.InvalidatingPagingSourceFactory
import androidx.paging.PagingSource
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
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
import org.mobilenativefoundation.store6.mutations.mutationStore
import org.mobilenativefoundation.store6.mutations.mutatorRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

class PagingMutationTracerTest {
    /**
     * [MutationStore.stream] fences a resolved key on its latest projection stamp before
     * delegating. A paging load started after [MutationStore.mutate] returns therefore cannot
     * race ahead of that key's optimistic projection.
     */
    @Test
    fun pagerOverMutationStore_observesOverlayFrameAfterMutate_thenSotAfterAdoption() = runTest {
        val key = TraceKey.Page(TRACE_QUERY, cursor = null, limit = 1)
        val original = Page(listOf("original"), next = null, prev = null)
        val optimistic = Page(listOf("optimistic"), next = null, prev = null)
        val authoritative = Page(listOf("authoritative"), next = null, prev = null)
        val mutations = TraceMutations()
        val backend = TraceBackend().apply {
            seed(key, original)
            acknowledge(key, authoritative)
        }
        val store = traceStore(mutations, backend)
        val factory = store.tracePagingFactory()
        var draining: Deferred<Unit>? = null

        try {
            val generationA = factory()
            val generationAInvalidated = generationA.invalidationSignal()
            assertEquals(original.items, generationA.refresh().data)
            assertEquals(1, backend.fetchCount(key))

            store.stream(key, Freshness.LocalOnly).test {
                val initial = awaitData()
                assertEquals(original, initial.value)

                store.mutate(key, mutations.updatePage, "optimistic")
                val overlay = awaitData(optimistic, Origin.OVERLAY)
                assertEquals(Origin.OVERLAY, overlay.origin)
                generationAInvalidated.await()

                val generationB = factory()
                val generationBInvalidated = generationB.invalidationSignal()
                assertEquals(optimistic.items, generationB.refresh().data)
                assertEquals(1, backend.fetchCount(key))

                draining =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        store.drain(key)
                    }
                backend.pushStarted.await()
                assertFalse(checkNotNull(draining).isCompleted)
                backend.releasePush()

                val adopted = awaitData(authoritative, Origin.SOT)
                assertEquals(Origin.SOT, adopted.origin)
                checkNotNull(draining).await()
                assertEquals(0, store.pending(key).size)
                generationBInvalidated.await()

                val generationC = factory()
                assertEquals(authoritative.items, generationC.refresh().data)
                assertEquals(1, backend.fetchCount(key))
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            factory.invalidate()
            backend.releasePush()
            draining?.cancel()
            store.close()
            draining?.join()
        }
    }

    @Test
    fun entityKeyMutation_reachesPageViaStaleSetAdoption() = runTest {
        val pageA = TraceKey.Page(TRACE_QUERY, cursor = null, limit = 1)
        val pageB = TraceKey.Page(TRACE_QUERY, cursor = 1, limit = 1)
        val entity = TraceKey.Entity("entity-1")
        val originalA = Page(listOf("page-a-original"), next = 1, prev = null)
        val freshA = Page(listOf("page-a-fresh"), next = 1, prev = null)
        val firstB = Page(listOf("page-b-first"), next = null, prev = 0)
        val mutations = TraceMutations()
        val backend = TraceBackend().apply {
            seed(pageA, originalA)
            acknowledge(entity, Page(listOf("entity-authoritative"), null, null))
        }
        val store = traceStore(mutations, backend)
        val factory = store.tracePagingFactory()
        var draining: Deferred<Unit>? = null

        try {
            val generationA = factory()
            val generationAInvalidated = generationA.invalidationSignal()
            assertEquals(originalA.items, generationA.refresh().data)
            assertEquals(1, backend.fetchCount(pageA))
            assertEquals(0, backend.fetchCount(pageB))

            backend.seed(pageA, freshA)
            backend.seed(pageB, firstB)
            store.mutate(entity, mutations.updateEntity, "entity-optimistic")
            draining =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.drain(entity)
                }
            backend.pushStarted.await()
            backend.releasePush()
            checkNotNull(draining).await()
            assertEquals(0, store.pending(entity).size)
            generationAInvalidated.await()

            val generationB = factory()
            assertEquals(freshA.items, generationB.refresh().data)
            assertEquals(firstB.items, generationB.append(key = 1).data)
            assertEquals(2, backend.fetchCount(pageA))
            assertEquals(1, backend.fetchCount(pageB))
            assertEquals(0, backend.fetchCount(entity))
        } finally {
            factory.invalidate()
            backend.releasePush()
            draining?.cancel()
            store.close()
            draining?.join()
        }
    }

    @Test
    fun entityKeyMutation_doesNotOptimisticallyRewritePageValue() = runTest {
        val pageA = TraceKey.Page(TRACE_QUERY, cursor = null, limit = 1)
        val entity = TraceKey.Entity("entity-1")
        val original = Page(listOf("page-a-original"), next = null, prev = null)
        val entityOptimistic = Page(listOf("entity-optimistic"), next = null, prev = null)
        val mutations = TraceMutations()
        val backend = TraceBackend().apply {
            seed(pageA, original)
            acknowledge(entity, Page(listOf("entity-authoritative"), null, null))
        }
        val store = traceStore(mutations, backend)
        val factory = store.tracePagingFactory()

        try {
            val generation = factory()
            assertEquals(original.items, generation.refresh().data)
            assertEquals(1, backend.fetchCount(pageA))

            store.mutate(entity, mutations.updateEntity, entityOptimistic.items.single())
            store.stream(pageA, Freshness.LocalOnly).test {
                val observed = awaitData()
                assertEquals(original, observed.value)
                assertNotEquals(Origin.OVERLAY, observed.origin)
                assertEquals(1, backend.fetchCount(pageA))
                assertEquals(0, backend.fetchCount(entity))
                assertEquals(1, store.pending(entity).size)
                assertFalse(generation.invalid)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            factory.invalidate()
            backend.releasePush()
            store.close()
        }
    }
}

private sealed interface TraceKey : StoreKey {
    data class Page(
        val query: String,
        val cursor: Int?,
        val limit: Int,
    ) : TraceKey {
        override val namespace: StoreNamespace = StoreNamespace("pages:$query")

        override fun canonicalId(): String = "${cursor ?: "first"}+$limit"
    }

    data class Entity(
        val id: String,
    ) : TraceKey {
        override val namespace: StoreNamespace = ENTITIES_NAMESPACE

        override fun canonicalId(): String = id
    }
}

private class TraceMutations {
    lateinit var updatePage: MutatorRef<TraceKey, Page, String>
        private set
    lateinit var updateEntity: MutatorRef<TraceKey, Page, String>
        private set

    val registry =
        mutatorRegistry<TraceKey, Page> {
            updatePage =
                update(
                    id = "update-page",
                    version = 1,
                    codec = TraceStringCodec,
                    stales = { _, _ -> StaleSet(keys = emptySet(), namespaces = emptySet()) },
                ) { page, item -> page.copy(items = listOf(item)) }
            updateEntity =
                upsert(
                    id = "update-entity",
                    version = 1,
                    codec = TraceStringCodec,
                    stales = { _, _ ->
                        StaleSet(keys = emptySet(), namespaces = setOf(PAGES_NAMESPACE))
                    },
                ) { _, item ->
                    MutationPresence.Present(Page(listOf(item), next = null, prev = null))
                }
        }
}

private class TraceBackend : Fetcher<TraceKey, Page>, MutationServer<TraceKey, Page> {
    private val truth = MutableStateFlow<Map<TraceKey, Page>>(emptyMap())
    private val acknowledgements = MutableStateFlow<Map<TraceKey, Page>>(emptyMap())
    private val fetchCounts = MutableStateFlow<Map<TraceKey, Int>>(emptyMap())
    val pushStarted = CompletableDeferred<MutationPush<TraceKey, Page>>()
    private val pushRelease = CompletableDeferred<Unit>()

    fun seed(
        key: TraceKey,
        value: Page,
    ) {
        truth.update { current -> current + (key to value) }
    }

    fun acknowledge(
        key: TraceKey,
        value: Page,
    ) {
        acknowledgements.update { current -> current + (key to value) }
    }

    fun fetchCount(key: TraceKey): Int = fetchCounts.value[key] ?: 0

    fun releasePush() {
        pushRelease.complete(Unit)
    }

    override suspend fun fetch(
        key: TraceKey,
        etag: String?,
    ): FetcherResult<Page> {
        fetchCounts.update { current -> current + (key to ((current[key] ?: 0) + 1)) }
        return FetcherResult.Success(
            checkNotNull(truth.value[key]) {
                "No backend page for ${key.namespace.value}/${key.canonicalId()}."
            },
        )
    }

    override suspend fun push(request: MutationPush<TraceKey, Page>): MutationAck<TraceKey, Page> {
        pushStarted.complete(request)
        pushRelease.await()
        val authoritative =
            checkNotNull(acknowledgements.value[request.key]) {
                "No acknowledgement for ${request.identity.namespace}/${request.identity.canonicalId}."
            }
        truth.update { current -> current + (request.key to authoritative) }
        return MutationPresentAck(
            authoritative = authoritative,
            etag = "trace-etag",
            canonicalKey = null,
        )
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(request.retiredThroughSequence)
}

private object TraceKeyResolver : MutationKeyResolver<TraceKey> {
    override suspend fun resolve(identity: MutationKeyIdentity): TraceKey? =
        when {
            identity.namespace.startsWith(PAGES_NAMESPACE_PREFIX) -> {
                val parameters = identity.canonicalId.split('+')
                if (parameters.size != 2) {
                    null
                } else {
                    val cursor =
                        when (parameters[0]) {
                            "first" -> null
                            else -> parameters[0].toIntOrNull() ?: return null
                        }
                    val limit = parameters[1].toIntOrNull() ?: return null
                    TraceKey.Page(
                        query = identity.namespace.removePrefix(PAGES_NAMESPACE_PREFIX),
                        cursor = cursor,
                        limit = limit,
                    )
                }
            }
            identity.namespace == ENTITIES_NAMESPACE.value -> TraceKey.Entity(identity.canonicalId)
            else -> null
        }
}

private object TraceStringCodec : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): String {
        require(version == 1) { "Trace String values require version 1; was $version." }
        return bytes.decodeToString()
    }
}

private object TracePageCodec : MutationCodec<Page> {
    override fun encode(value: Page): ByteArray =
        buildList {
            add(value.next?.toString().orEmpty())
            add(value.prev?.toString().orEmpty())
            add(value.items.size.toString())
            addAll(value.items)
        }.joinToString(separator = CODEC_SEPARATOR).encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): Page {
        require(version == 1) { "Trace Page values require version 1; was $version." }
        val fields = bytes.decodeToString().split(CODEC_SEPARATOR)
        require(fields.size >= 3) { "Trace Page value requires three header fields." }
        val itemCount = fields[2].toInt()
        require(fields.size == itemCount + 3) {
            "Trace Page declared $itemCount items but encoded ${fields.size - 3}."
        }
        return Page(
            items = fields.drop(3),
            next = fields[0].ifEmpty { null }?.toInt(),
            prev = fields[1].ifEmpty { null }?.toInt(),
        )
    }
}

private fun traceStore(
    mutations: TraceMutations,
    backend: TraceBackend,
): MutationStore<TraceKey, Page> =
    mutationStore(
        registry = mutations.registry,
        server = backend,
        keyResolver = TraceKeyResolver,
        valueCodecVersion = 1,
        valueCodec = TracePageCodec,
    ) {
        fetcher(backend)
    }

private fun MutationStore<TraceKey, Page>.tracePagingFactory():
    InvalidatingPagingSourceFactory<Int, String> =
    pagingSourceFactory {
        pageKey { paginationKey, loadSize -> TraceKey.Page(TRACE_QUERY, paginationKey, loadSize) }
        items { value -> value.items }
        nextKey { _, value -> value.next }
        prevKey { _, value -> value.prev }
    }

private suspend fun PagingSource<Int, String>.refresh(): PagingSource.LoadResult.Page<Int, String> =
    assertIs(
        load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 1,
                placeholdersEnabled = false,
            ),
        ),
    )

private suspend fun PagingSource<Int, String>.append(
    key: Int,
): PagingSource.LoadResult.Page<Int, String> =
    assertIs(
        load(
            PagingSource.LoadParams.Append(
                key = key,
                loadSize = 1,
                placeholdersEnabled = false,
            ),
        ),
    )

private fun PagingSource<Int, String>.invalidationSignal(): CompletableDeferred<Unit> =
    CompletableDeferred<Unit>().also { signal ->
        registerInvalidatedCallback { signal.complete(Unit) }
    }

private suspend fun ReceiveTurbine<StoreResult<Page>>.awaitData(): StoreResult.Data<Page> {
    while (true) {
        val result = awaitItem()
        if (result is StoreResult.Data) return result
    }
}

private suspend fun ReceiveTurbine<StoreResult<Page>>.awaitData(
    value: Page,
    origin: Origin,
): StoreResult.Data<Page> {
    while (true) {
        val result = awaitData()
        if (result.value == value && result.origin == origin) return result
    }
}

private const val TRACE_QUERY = "trace"
private const val PAGES_NAMESPACE_PREFIX = "pages:"
private val PAGES_NAMESPACE = StoreNamespace("$PAGES_NAMESPACE_PREFIX$TRACE_QUERY")
private val ENTITIES_NAMESPACE = StoreNamespace("entities")
private const val CODEC_SEPARATOR = "\u0000"
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
