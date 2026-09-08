@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    org.mobilenativefoundation.store.core5.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store.store5.mutablestore

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreWriteResponse
import org.mobilenativefoundation.store.store5.UpdaterResult
import org.mobilenativefoundation.store.store5.mutablestore.util.MutableStoreRaceFixture
import org.mobilenativefoundation.store.store5.mutablestore.util.RaceGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MutableStoreAcknowledgementTest {
    @Test
    fun newerWriteSurvivesAcknowledgementCommit() = runTest {
        val acknowledgement = RaceGate()
        val bLocalReturn = RaceGate()
        val fixture = MutableStoreRaceFixture(
            scope = this,
            post = { _, value -> UpdaterResult.Success.Typed("ack:$value") },
            beforeLocalReturn = { _, value ->
                if (value == "B") bLocalReturn.pause()
            },
            beforeAcknowledgementCommit = { acknowledgement.pause() },
        )

        val a = async { fixture.store.write(fixture.request("A", 1L)) }
        acknowledgement.entered.await()
        val b = async { fixture.store.write(fixture.request("B", 2L)) }
        bLocalReturn.entered.await()
        assertEquals("B", fixture.local.value["key"])

        acknowledgement.open()
        runCurrent()
        // Fixed acknowledgment can be waiting for B's local transaction here.
        bLocalReturn.open()

        assertIs<StoreWriteResponse.Success>(a.await())
        assertIs<StoreWriteResponse.Success>(b.await())
        assertEquals(listOf("key" to "A", "key" to "B"), fixture.posted)
        assertEquals("B", fixture.remote["key"])
        assertEquals(
            listOf<Pair<String, StoreWriteResponse.Success>>(
                "A" to StoreWriteResponse.Success.Typed("ack:A"),
                "B" to StoreWriteResponse.Success.Typed("ack:B"),
            ),
            fixture.successes,
        )
    }

    @Test
    fun olderEagerSuccessDoesNotAcknowledgeNewerFailedWrite() = runTest {
        val eagerA = RaceGate()
        var aPosts = 0
        var failB = true
        val fixture = MutableStoreRaceFixture(
            scope = this,
            post = { _, value ->
                when {
                    value == "A" && ++aPosts == 1 -> UpdaterResult.Error.Message("offline:A")
                    value == "A" -> {
                        eagerA.pause()
                        UpdaterResult.Success.Typed("ack:A")
                    }
                    failB -> UpdaterResult.Error.Message("offline:B")
                    else -> UpdaterResult.Success.Typed("ack:B")
                }
            },
        )
        assertIs<StoreWriteResponse.Error>(fixture.store.write(fixture.request("A", 1L)))
        val reader = async {
            fixture.store.stream<String>(StoreReadRequest.localOnly("key"))
                .first { it is StoreReadResponse.Data }
        }
        eagerA.entered.await()
        val b = async { fixture.store.write(fixture.request("B", 2L)) }
        runCurrent()
        assertEquals("B", fixture.local.value["key"])
        assertEquals(0, fixture.successes.count { it.first == "B" })

        // B's remote attempt waits for A after the fix. Do not await B yet.
        eagerA.open()
        reader.await()
        assertEquals(StoreWriteResponse.Error.Message("offline:B"), b.await())
        assertEquals(0, fixture.successes.count { it.first == "B" })
        assertNotNull(fixture.bookkeeper.getLastFailedSync("key"))

        failB = false
        fixture.store.stream<String>(StoreReadRequest.localOnly("key"))
            .first { it is StoreReadResponse.Data }
        assertEquals("B", fixture.remote["key"])
        assertEquals(
            listOf<Pair<String, StoreWriteResponse.Success>>(
                "B" to StoreWriteResponse.Success.Typed("ack:B"),
            ),
            fixture.successes.filter { it.first == "B" },
        )
        assertNull(fixture.bookkeeper.getLastFailedSync("key"))
    }
}
