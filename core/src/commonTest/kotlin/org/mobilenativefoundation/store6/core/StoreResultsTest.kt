package org.mobilenativefoundation.store6.core

import org.mobilenativefoundation.store6.core.seam.StoreResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalStoreApi::class)
class StoreResultsTest {

    @Test
    fun resultFactories_roundTripEveryPayload() {
        assertIs<StoreResult.Loading>(StoreResults.loading())

        val data = StoreResults.data(
            value = "v",
            origin = Origin.MEMORY,
            age = 2.seconds,
            isStale = false,
            refreshing = true,
        )
        assertEquals("v", data.value)
        assertEquals(Origin.MEMORY, data.origin)
        assertEquals(2.seconds, data.age)
        assertFalse(data.isStale)
        assertTrue(data.refreshing)

        val nullableData: StoreResult.Data<String?> = StoreResults.data(
            value = null,
            origin = Origin.OVERLAY,
            age = 3.seconds,
            isStale = true,
            refreshing = false,
        )
        assertNull(nullableData.value)
        assertEquals(Origin.OVERLAY, nullableData.origin)
        assertEquals(3.seconds, nullableData.age)
        assertTrue(nullableData.isStale)
        assertFalse(nullableData.refreshing)

        assertEquals(4.seconds, StoreResults.revalidated(age = 4.seconds).age)

        val error: StoreError.Fetch = StoreResults.fetchError(message = "fetch")
        val wrapped: StoreResult.Error = StoreResults.error(error = error, servedStale = true)
        assertSame(error, wrapped.error)
        assertTrue(wrapped.servedStale)
    }

    @Test
    fun errorFactories_roundTripEveryPayload() {
        val fetchCause = IllegalStateException("fetch cause")
        val fetch = StoreResults.fetchError(message = "fetch", cause = fetchCause)
        assertEquals("fetch", fetch.message)
        assertSame(fetchCause, fetch.cause)
        assertNull(StoreResults.fetchError(message = "fetch default").cause)
        assertNull(StoreResults.fetchError(message = "fetch null", cause = null).cause)

        val persistenceCause = IllegalArgumentException("persistence cause")
        val persistence = StoreResults.persistenceError(
            message = "persistence",
            cause = persistenceCause,
        )
        assertEquals("persistence", persistence.message)
        assertSame(persistenceCause, persistence.cause)
        assertNull(StoreResults.persistenceError(message = "persistence default").cause)
        assertNull(StoreResults.persistenceError(message = "persistence null", cause = null).cause)

        val conversionCause = UnsupportedOperationException("conversion cause")
        val conversion = StoreResults.conversionError(
            message = "conversion",
            cause = conversionCause,
        )
        assertEquals("conversion", conversion.message)
        assertSame(conversionCause, conversion.cause)
        assertNull(StoreResults.conversionError(message = "conversion default").cause)
        assertNull(StoreResults.conversionError(message = "conversion null", cause = null).cause)

        val freshness = StoreResults.freshnessUnsatisfiable(message = "freshness")
        assertEquals("freshness", freshness.message)

        val serverMeta = object : StoreMeta {
            override val writtenAtEpochMillis: Long = 42L
            override val etag: String = "etag"
        }
        val conflict = StoreResults.conflict(serverMeta = serverMeta, message = "conflict")
        assertSame(serverMeta, conflict.serverMeta)
        assertEquals("conflict", conflict.message)
        assertNull(StoreResults.conflict(serverMeta = null, message = "no metadata").serverMeta)

        val key = TestKey("missing")
        val missing = StoreResults.missing(key = key, message = "missing")
        assertSame(key, missing.key)
        assertEquals("missing", missing.message)
    }

    @Test
    fun exceptionFactory_roundTripsErrorCauseAndMessage() {
        val error: StoreError.Persistence = StoreResults.persistenceError(message = "persistence")
        val cause = IllegalStateException("exception cause")
        val exception: StoreException = StoreResults.exception(error = error, cause = cause)

        assertSame(error, exception.error)
        assertSame(cause, exception.cause)
        assertEquals("persistence", exception.message)
        assertNull(StoreResults.exception(error).cause)

        assertEquals("m", StoreResults.exception(StoreResults.fetchError("m")).message)
        assertIs<StoreError.Fetch>(StoreResults.exception(StoreResults.fetchError("m")).error)
    }
}
