@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.sqldelight

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectDisposition
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectKind
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class SqlDelightJournalRestartTest {
    @Test
    fun durableAlgebraSurvivesAdapterRecreationOnSameDriver() = runTest {
        val harness = freshJournalHarness()
        try {
            val first = harness.storage()
            first.transaction { transaction ->
                transaction.insertClient(MutationClientRecord(1, "client", 0, 0, 0, 10))
                transaction.advanceClient(MutationClientRecord(1, "client", 1, 0, 0, 10))
                transaction.insertIntent(
                    recordVersion = 1,
                    clientId = "client",
                    clientSequence = 1,
                    mutationId = "mutation-1",
                    namespace = "items",
                    canonicalId = "a",
                    mutatorId = "upsert",
                    mutatorVersion = 1,
                    argsBlob = byteArrayOf(1, 2, 3),
                    idempotencyRoot = "client:1",
                    createdAt = 11,
                )
                transaction.insertExecution(
                    MutationExecutionRecord(
                        "client", 1, MutationExecutionPhase.UNPREPARED, 0, 0, null, null, null,
                    ),
                )
                transaction.insertEffect(
                    MutationEffectRecord(
                        "client", 1, 0, MutationEffectKind.KEY, "items", "a", 12,
                        MutationEffectDisposition.PENDING, null,
                    ),
                )
            }

            val reopened = harness.storage()
            reopened.transaction { transaction ->
                assertEquals(1L, transaction.client("client")?.lastAllocatedSequence)
                assertEquals("mutation-1", transaction.intents("client").single().mutationId)
                assertContentEquals(
                    byteArrayOf(1, 2, 3),
                    transaction.intents("client").single().argsBlob,
                )
                assertEquals(
                    MutationExecutionPhase.UNPREPARED,
                    transaction.executions("client").single().phase,
                )
                assertEquals(MutationEffectKind.KEY, transaction.effects("client").single().kind)
            }
        } finally {
            harness.driver.close()
        }
    }
}
