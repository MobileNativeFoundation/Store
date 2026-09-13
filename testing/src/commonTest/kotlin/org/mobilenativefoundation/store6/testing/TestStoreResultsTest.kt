package org.mobilenativefoundation.store6.testing

import org.mobilenativefoundation.store6.core.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalStoreApi::class)
class TestStoreResultsTest {
    @Test
    fun factories_constructEveryResultStateAndErrorVariant_withoutInternals() {
        val key = TestingKey("test", "1")
        val results: List<StoreResult<String>> = listOf(
            TestStoreResults.loading(),
            TestStoreResults.data("v"),
            TestStoreResults.data("v", origin = Origin.SOT, age = 5.seconds, isStale = true, refreshing = true),
            TestStoreResults.revalidated(),
            TestStoreResults.error(TestStoreResults.fetchError("fetch test/1 failed: scripted. Fix the script.")),
            TestStoreResults.error(TestStoreResults.persistenceError("persist test/1 failed: scripted. Fix the script."), servedStale = true),
            TestStoreResults.error(TestStoreResults.conversionError("convert test/1 failed: scripted. Fix the script.")),
            TestStoreResults.error(TestStoreResults.freshnessUnsatisfiable("MustBeFresh for test/1 failed: scripted. Fix the script.")),
            TestStoreResults.error(TestStoreResults.conflict(TestStoreMeta(writtenAtEpochMillis = 1L, etag = "e"), "write test/1 conflicted: scripted. Fix the script.")),
            TestStoreResults.error(TestStoreResults.missing(key, "no value for test/1: scripted. Fix the script.")),
        )
        results.forEach { result ->
            when (result) { // exhaustive consumer-side when over the sealed vocabulary
                is StoreResult.Loading, is StoreResult.Data, is StoreResult.Revalidated, is StoreResult.Error -> Unit
            }
        }
        val exception = TestStoreResults.exception(TestStoreResults.missing(key, "no value for test/1: scripted. Fix the script."))
        assertIs<StoreError.Missing>(exception.error)
        assertTrue(exception.message!!.contains("test/1"))
    }
}
