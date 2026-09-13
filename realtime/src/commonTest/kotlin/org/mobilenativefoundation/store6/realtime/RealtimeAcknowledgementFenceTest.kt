@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.FreshnessEvidence
import org.mobilenativefoundation.store6.core.seam.SourceAdoption
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.core.seam.runtime
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.testing.FakeBookkeeper
import org.mobilenativefoundation.store6.testing.FakeSourceOfTruth
import org.mobilenativefoundation.store6.testing.TestStoreMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class RealtimeAcknowledgementFenceTest {
    @Test
    fun oldFetchBetweenLegacyApplyAndConfirmation_keepsValueAndEtagCoherent() = runTest(timeout = 25.seconds) {
        val key = RealtimeTestKey("commit-fence")
        val rows = FakeSourceOfTruth<RealtimeTestKey, String>()
        val bookkeeper = FakeBookkeeper()
        rows.write(key, "seed")
        bookkeeper.recordSuccess(key, TestStoreMeta(1L, "seed-etag"))
        val fetchEntered = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val adopted = CompletableDeferred<Unit>()
        val releaseLegacyApply = CompletableDeferred<Unit>()
        val etags = mutableListOf<String?>()
        val persistence = object : SourceOfTruth<RealtimeTestKey, String> by rows {
            override suspend fun write(key: RealtimeTestKey, value: String) {
                if (value == "pushed") {
                    writeEntered.complete(Unit)
                    releaseWrite.await()
                }
                rows.write(key, value)
            }
        }
        val fetcher = object : Fetcher<RealtimeTestKey, String> {
            override suspend fun fetch(key: RealtimeTestKey, etag: String?): FetcherResult<String> {
                etags += etag
                return if (etags.size == 1) {
                    fetchEntered.complete(Unit)
                    releaseFetch.await()
                    FetcherResult.Success("old-fetch", "old-etag")
                } else {
                    FetcherResult.NotModified(etag)
                }
            }
        }
        val store = store<RealtimeTestKey, String> {
            fetcher(fetcher)
            persistence(persistence)
            bookkeeper(bookkeeper)
        }
        val actual = requireNotNull(store.runtime()).writeHandle
        val handle = object : StoreWriteHandle<RealtimeTestKey, String> by actual {
            override suspend fun apply(key: RealtimeTestKey, value: String) {
                actual.apply(key, value)
                adopted.complete(Unit)
                releaseLegacyApply.await()
            }

            override suspend fun applyAcknowledgement(
                key: RealtimeTestKey,
                value: String,
                etag: String?,
                freshnessEvidence: FreshnessEvidence?,
                adoption: SourceAdoption?,
            ) {
                actual.applyAcknowledgement(key, value, etag, freshnessEvidence, adoption)
                adopted.complete(Unit)
            }
        }
        val binding = RealtimeBinding(store, handle)
        try {
            assertEquals("seed", store.get(key, Freshness.LocalOnly))
            val oldFetch = async { store.get(key, Freshness.MustBeFresh) }
            fetchEntered.await()
            val upsert = async { binding.apply(RealtimeMessage.Upsert(key, "pushed", "push-etag")) }
            writeEntered.await()
            releaseFetch.complete(Unit)
            releaseWrite.complete(Unit)
            adopted.await()
            assertEquals("old-fetch", oldFetch.await())
            releaseLegacyApply.complete(Unit)
            upsert.await()

            assertEquals("old-fetch", store.get(key, Freshness.LocalOnly))
            assertEquals("old-etag", bookkeeper.status(key)?.meta?.etag)
            assertEquals("old-fetch", store.get(key, Freshness.MustBeFresh))
            assertEquals("old-etag", etags.last())
        } finally {
            releaseFetch.complete(Unit)
            releaseWrite.complete(Unit)
            releaseLegacyApply.complete(Unit)
            store.close()
        }
    }

    @Test
    fun separateBindings_cannotPairOneWritersValueWithAnotherEtag() = runTest(timeout = 25.seconds) {
        val key = RealtimeTestKey("separate-bindings")
        val rows = FakeSourceOfTruth<RealtimeTestKey, String>()
        val bookkeeper = FakeBookkeeper()
        val store = store<RealtimeTestKey, String> {
            persistence(rows)
            bookkeeper(bookkeeper)
            fetcher { error("Binding acknowledgements must not fetch") }
        }
        val actual = requireNotNull(store.runtime()).writeHandle
        val adopted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstHandle = object : StoreWriteHandle<RealtimeTestKey, String> by actual {
            override suspend fun apply(key: RealtimeTestKey, value: String) {
                actual.apply(key, value)
                adopted.complete(Unit)
                releaseFirst.await()
            }

            override suspend fun applyAcknowledgement(
                key: RealtimeTestKey,
                value: String,
                etag: String?,
                freshnessEvidence: FreshnessEvidence?,
                adoption: SourceAdoption?,
            ) {
                actual.applyAcknowledgement(key, value, etag, freshnessEvidence, adoption)
                adopted.complete(Unit)
                releaseFirst.await()
            }
        }
        val first = RealtimeBinding(store, firstHandle)
        val second = realtimeBinding(store)
        try {
            val firstWrite = async { first.apply(RealtimeMessage.Upsert(key, "first", "first-etag")) }
            adopted.await()
            second.apply(RealtimeMessage.Upsert(key, "second", "second-etag"))
            releaseFirst.complete(Unit)
            firstWrite.await()

            assertEquals("second", store.get(key, Freshness.LocalOnly))
            assertEquals("second-etag", bookkeeper.status(key)?.meta?.etag)
        } finally {
            releaseFirst.complete(Unit)
            store.close()
        }
    }

}
