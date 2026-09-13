@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.sqldelight

import kotlinx.coroutines.Dispatchers
import org.mobilenativefoundation.store6.core.EmissionSequenceConformanceTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.FreshnessPolicyConformanceTest
import org.mobilenativefoundation.store6.core.SingleFlightConformanceTest
import org.mobilenativefoundation.store6.core.StoreBuilder
import org.mobilenativefoundation.store6.core.StoreConformanceTest
import org.mobilenativefoundation.store6.core.StoreInvalidationConformanceTest
import org.mobilenativefoundation.store6.core.StoreInvalidationStressConformance
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreRevalidationConformance
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest

class StoreConformanceAgainstSqlDelightSotTest : StoreConformanceTest() {
    private val installer = BorrowedSotInstaller()

    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.persistence(trackedSot(installer.create()))
    }

    @AfterTest
    fun closeHarnesses() {
        installer.closeAll()
    }
}

class StoreInvalidationConformanceAgainstSqlDelightSotTest : StoreInvalidationConformanceTest() {
    private val installer = BorrowedSotInstaller()

    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.persistence(trackedSot(installer.create()))
    }

    @AfterTest
    fun closeHarnesses() {
        installer.closeAll()
    }
}

class EmissionSequenceConformanceAgainstSqlDelightSotTest : EmissionSequenceConformanceTest() {
    private val installer = BorrowedSotInstaller()

    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.persistence(trackedSot(installer.create()))
    }

    @AfterTest
    fun closeHarnesses() {
        installer.closeAll()
    }
}

class SingleFlightConformanceAgainstSqlDelightSotTest : SingleFlightConformanceTest() {
    private val installer = BorrowedSotInstaller()

    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        installer.install(builder)
    }

    @AfterTest
    fun closeHarnesses() {
        installer.closeAll()
    }
}

class FreshnessPolicyConformanceAgainstSqlDelightSotTest : FreshnessPolicyConformanceTest() {
    private val installer = BorrowedSotInstaller()

    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        builder.persistence(trackedSot(installer.create()))
    }

    @AfterTest
    fun closeHarnesses() {
        installer.closeAll()
    }
}

class StoreRevalidationConformanceAgainstSqlDelightSotTest : StoreRevalidationConformance() {
    private val installer = BorrowedSotInstaller()

    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        installer.install(builder)
    }

    @AfterTest
    fun closeHarnesses() {
        installer.closeAll()
    }
}

class StoreInvalidationStressAgainstSqlDelightSotTest : StoreInvalidationStressConformance() {
    private val installer = BorrowedSotInstaller()

    override fun <K : StoreKey, V : Any> installSot(builder: StoreBuilder<K, V>) {
        installer.install(builder)
    }

    @AfterTest
    fun closeHarnesses() {
        installer.closeAll()
    }
}

internal class BorrowedSotInstaller {
    private val harnesses = mutableListOf<SqlHarness>()

    fun <K : StoreKey, V : Any> install(builder: StoreBuilder<K, V>) {
        builder.persistence(create())
    }

    fun <K : StoreKey, V : Any> create(): SourceOfTruth<K, V> {
        // The byte-identical borrowed scenarios establish enrollment and publication with their own
        // observable causal barriers. An extra test-fixture dispatcher hop can move the SQL read past
        // those barriers under a saturated full-root build. Keep the real driver on the caller context
        // for this borrowed suite only; production and adapter-owned fixtures retain Dispatchers.Default.
        val harness = freshHarness().also { harnesses += it }
        return sqlDelightTestSot(
            harness = harness,
            readContext = EmptyCoroutineContext,
        )
    }

    fun <K : StoreKey, V : Any> createHopping(): SourceOfTruth<K, V> {
        val harness = freshHarness().also { harnesses += it }
        return PostCaptureReaderHopSourceOfTruth(
            sqlDelightTestSot(
                harness = harness,
                readContext = Dispatchers.Default,
            ),
        )
    }

    fun closeAll() {
        var firstFailure: Throwable? = null
        harnesses.forEach { harness ->
            try {
                harness.driver.close()
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        harnesses.clear()
        firstFailure?.let { throw it }
    }
}
