@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.room

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.testing.TestStoreMeta
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class RoomBookkeeperCancellationTest {
    @Test
    fun maintenance_cancelledBeforeEntry_doesNotChangeRecordsOrWatermarks() = runTest {
        val key = RoomKitKey(StoreNamespace("cancel-before-entry"), "key")
        val operations: List<suspend (Bookkeeper) -> Unit> = listOf(
            { it.markStale(key) },
            { it.advanceStaleWatermark(key.namespace) },
            { it.advanceGlobalStaleWatermark() },
            { it.forgetNamespace(key.namespace) },
            { it.forgetAll() },
        )
        for (operation in operations) {
            val database = createTestDatabase()
            try {
                val bookkeeper = RoomBookkeeper(database, database.store6BookkeeperDao())
                bookkeeper.recordSuccess(key, TestStoreMeta(10L, "before"))
                var completion: Result<Unit>? = null
                val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                    currentCoroutineContext().job.cancel()
                    completion = runCatching { operation(bookkeeper) }
                }
                caller.join()
                assertTrue(caller.isCancelled)
                assertIs<CancellationException>(assertNotNull(completion).exceptionOrNull())
                val after = assertNotNull(bookkeeper.status(key))
                assertEquals("before", after.meta?.etag)
                assertEquals(1L, after.lastSuccessSequence)
                assertFalse(after.durablyStale)
                assertTrue(currentCoroutineContext().job.isActive)
            } finally {
                database.close()
            }
        }
    }
}
