package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalStoreApi::class)
class StoreBuilderPersistenceTest {
    @Test
    fun persistenceRegistration_wiresFetchedWritesIntoTheSelectedSourceOfTruth() = runTest {
        val sourceOfTruth = InMemorySourceOfTruth<TestKey, String>()
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "fetched" }
        }

        assertEquals("fetched", store.get(TestKey("key")))
        sourceOfTruth.reader(TestKey("key")).test {
            assertEquals("fetched", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun prepopulatedPersistence_localOnlyGetHydratesWithoutFetching() = runTest {
        val sourceOfTruth = InMemorySourceOfTruth<TestKey, String>()
        val key = TestKey("key")
        sourceOfTruth.write(key, "durable")
        var fetchCalls = 0
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher {
                fetchCalls += 1
                "network"
            }
        }

        try {
            assertEquals("durable", store.get(key, Freshness.LocalOnly))
            assertEquals(0, fetchCalls)
        } finally {
            store.close()
        }
    }
}
