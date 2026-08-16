package org.mobilenativefoundation.store6.sqldelight

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class SqlDelightBookkeeperRestartTest {
    @Test
    fun durableAlgebra_survivesBookkeeperRecreation_onSameDriver() = runTest {
        val harness = freshHarness()
        try {
            val markedKey = TestKey("marked", "key")
            val namespaceWatermarkKey = TestKey("namespace-watermark", "never-seen")
            val globalWatermarkKey = TestKey("global-watermark", "never-seen")
            val laterSuccessKey = TestKey("success", "later")
            val markedMeta = TestMeta(writtenAtEpochMillis = 10L, etag = "marked")
            val namespaceMeta = TestMeta(writtenAtEpochMillis = 20L, etag = "namespace")
            val laterMeta = TestMeta(writtenAtEpochMillis = 30L, etag = "later")

            val first = SqlDelightBookkeeper(harness.driver, harness.transacter)
            first.advanceGlobalStaleWatermark()
            first.recordSuccess(markedKey, markedMeta)
            first.markStale(markedKey)
            first.recordSuccess(namespaceWatermarkKey, namespaceMeta)
            first.advanceStaleWatermark(namespaceWatermarkKey.namespace)
            first.recordSuccess(laterSuccessKey, laterMeta)

            val second = SqlDelightBookkeeper(harness.driver, harness.transacter)

            val markedStatus = assertNotNull(second.status(markedKey))
            assertEquals(markedMeta.writtenAtEpochMillis, markedStatus.meta?.writtenAtEpochMillis)
            assertEquals(markedMeta.etag, markedStatus.meta?.etag)
            assertTrue(markedStatus.durablyStale)

            val namespaceStatus = assertNotNull(second.status(namespaceWatermarkKey))
            assertEquals(
                namespaceMeta.writtenAtEpochMillis,
                namespaceStatus.meta?.writtenAtEpochMillis,
            )
            assertEquals(namespaceMeta.etag, namespaceStatus.meta?.etag)
            assertTrue(namespaceStatus.durablyStale)

            val globalStatus = assertNotNull(second.status(globalWatermarkKey))
            assertNull(globalStatus.meta)
            assertTrue(globalStatus.durablyStale)

            val laterStatus = assertNotNull(second.status(laterSuccessKey))
            assertEquals(laterMeta.writtenAtEpochMillis, laterStatus.meta?.writtenAtEpochMillis)
            assertEquals(laterMeta.etag, laterStatus.meta?.etag)
            assertFalse(laterStatus.durablyStale)

            val restartedMeta = TestMeta(writtenAtEpochMillis = 40L, etag = "restarted")
            second.recordSuccess(namespaceWatermarkKey, restartedMeta)
            val restartedStatus = assertNotNull(second.status(namespaceWatermarkKey))
            assertEquals(
                restartedMeta.writtenAtEpochMillis,
                restartedStatus.meta?.writtenAtEpochMillis,
            )
            assertEquals(restartedMeta.etag, restartedStatus.meta?.etag)
            assertFalse(restartedStatus.durablyStale)
        } finally {
            harness.driver.close()
        }
    }

    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    private class TestMeta(
        override val writtenAtEpochMillis: Long,
        override val etag: String?,
    ) : StoreMeta
}
