@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.room

import kotlinx.coroutines.flow.map
import org.mobilenativefoundation.store6.core.seam.TransactionalSourceOfTruth
import org.mobilenativefoundation.store6.testing.BookkeeperFaultFixture
import org.mobilenativefoundation.store6.testing.BookkeeperStatusFaultFixture
import org.mobilenativefoundation.store6.testing.MutationFaultInjector
import org.mobilenativefoundation.store6.testing.MutationFaultPoint
import org.mobilenativefoundation.store6.testing.SourceOfTruthFaultFixture

internal fun roomContractSourceFaultFixture(
    transactionBoundary: Boolean = false,
): SourceOfTruthFaultFixture<RoomKitKey, String> {
    val database = createTestDatabase()
    val dao = database.kitRowDao()
    val faults = MutationFaultInjector()
    fun afterMutation() {
        if (!transactionBoundary) faults.reach(MutationFaultPoint.BeforeCommit)
    }
    val source = RoomSourceOfTruth<RoomKitKey, String>(
        database = database,
        rowReader = { key -> dao.row(key.namespace.value, key.canonicalId()).map { it?.payload } },
        rowWriter = { key, value ->
            dao.upsert(KitRowEntity(key.namespace.value, key.canonicalId(), value))
            afterMutation()
        },
        rowDeleter = { key ->
            dao.delete(key.namespace.value, key.canonicalId())
            afterMutation()
        },
        namespaceDeleter = { namespace ->
            dao.deleteNamespace(namespace.value)
            afterMutation()
        },
        allDeleter = {
            dao.deleteAll()
            afterMutation()
        },
    )
    val fixtureSource = if (transactionBoundary) {
        object : TransactionalSourceOfTruth<RoomKitKey, String> by source {
            override suspend fun <R> withTransaction(block: suspend () -> R): R =
                source.withTransaction {
                    val result = block()
                    faults.reach(MutationFaultPoint.BeforeCommit)
                    result
                }
        }
    } else {
        source
    }
    return SourceOfTruthFaultFixture(fixtureSource, faults, database::close)
}

internal fun roomContractBookkeeperFaultFixture(): BookkeeperFaultFixture {
    val database = createTestDatabase()
    val faults = MutationFaultInjector()
    val dao = ContractBookkeeperDao(database.store6BookkeeperDao(), faults)
    return BookkeeperFaultFixture(RoomBookkeeper(database, dao), faults, database::close)
}

internal fun roomContractStatusFaultFixture(): BookkeeperStatusFaultFixture {
    val database = createTestDatabase()
    val dao = ContractBookkeeperDao(database.store6BookkeeperDao(), MutationFaultInjector())
    return BookkeeperStatusFaultFixture(RoomBookkeeper(database, dao), dao::armStatusRead, database::close)
}

private class ContractBookkeeperDao(
    private val delegate: Store6BookkeeperDao,
    private val faults: MutationFaultInjector,
) : Store6BookkeeperDao by delegate {
    private var statusReadCallback: (() -> Unit)? = null

    fun armStatusRead(action: () -> Unit) {
        check(statusReadCallback == null)
        statusReadCallback = action
    }

    override suspend fun record(namespace: String, canonicalId: String): Store6BookkeepingEntity? {
        val record = delegate.record(namespace, canonicalId)
        val action = statusReadCallback
        statusReadCallback = null
        action?.invoke()
        return record
    }

    override suspend fun upsertRecord(record: Store6BookkeepingEntity) {
        delegate.upsertRecord(record)
        faults.reach(MutationFaultPoint.BeforeCommit)
    }

    override suspend fun upsertWatermark(watermark: Store6WatermarkEntity) {
        delegate.upsertWatermark(watermark)
        // Allocation precedes the operation's final record or covering-watermark write.
        if (watermark.scope != "store6.sequence") faults.reach(MutationFaultPoint.BeforeCommit)
    }

    override suspend fun deleteNamespaceRecords(namespace: String) {
        delegate.deleteNamespaceRecords(namespace)
        faults.reach(MutationFaultPoint.BeforeCommit)
    }

    override suspend fun deleteAllRecords() {
        delegate.deleteAllRecords()
        faults.reach(MutationFaultPoint.BeforeCommit)
    }
}
