@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    org.mobilenativefoundation.store.core5.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store.store5.mutablestore

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.SourceOfTruth
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

    @Test
    fun failedLocalWriteIsNeverPostedOrAcknowledged() = runTest {
        val sequential = MutableStoreRaceFixture(
            scope = this,
            post = { _, value -> UpdaterResult.Success.Typed("ack:$value") },
            beforeLocalWrite = { _, value ->
                if (value == "B") error("local:B")
            },
        )

        val failedB = assertIs<StoreWriteResponse.Error.Exception>(
            sequential.store.write(sequential.request("B", 1L)),
        )
        val writeException = assertIs<SourceOfTruth.WriteException>(failedB.error)
        assertEquals("local:B", assertIs<IllegalStateException>(writeException.cause).message)
        assertIs<StoreWriteResponse.Success>(sequential.store.write(sequential.request("A", 2L)))
        assertEquals(listOf("key" to "A"), sequential.localWrites)
        assertEquals(listOf("key" to "A"), sequential.posted)
        assertEquals(
            listOf<Pair<String, StoreWriteResponse.Success>>(
                "A" to StoreWriteResponse.Success.Typed("ack:A"),
            ),
            sequential.successes,
        )

        val aLocalReturn = RaceGate()
        val overlapping = MutableStoreRaceFixture(
            scope = this,
            post = { _, value -> UpdaterResult.Success.Typed("ack:$value") },
            beforeLocalWrite = { _, value ->
                if (value == "B") error("local:B")
            },
            beforeLocalReturn = { _, value ->
                if (value == "A") aLocalReturn.pause()
            },
        )
        val a = async { overlapping.store.write(overlapping.request("A", 3L)) }
        aLocalReturn.entered.await()
        val b = async { overlapping.store.write(overlapping.request("B", 4L)) }
        runCurrent()
        assertEquals(listOf("key" to "A"), overlapping.localAttempts)

        aLocalReturn.open()
        assertIs<StoreWriteResponse.Success>(a.await())
        val overlappingFailedB = assertIs<StoreWriteResponse.Error.Exception>(b.await())
        assertIs<SourceOfTruth.WriteException>(overlappingFailedB.error)
        assertEquals(listOf("key" to "A"), overlapping.localWrites)
        assertEquals(listOf("key" to "A"), overlapping.posted)
        assertEquals(0, overlapping.successes.count { it.first == "B" })
    }

    @Test
    fun acknowledgementIgnoresCreatedOrder() = runTest {
        val scope = this

        suspend fun verifyOrder(
            aCreated: Long,
            bCreated: Long,
        ) {
            var attempt = 0
            val fixture = MutableStoreRaceFixture(
                scope = scope,
                post = { _, value ->
                    if (++attempt == 1) {
                        UpdaterResult.Error.Message("offline:$value")
                    } else {
                        UpdaterResult.Success.Typed("ack:$value")
                    }
                },
            )

            assertIs<StoreWriteResponse.Error>(fixture.store.write(fixture.request("A", aCreated)))
            assertEquals(
                StoreWriteResponse.Success.Typed("ack:B"),
                fixture.store.write(fixture.request("B", bCreated)),
            )
            assertEquals(listOf("key" to "A", "key" to "B"), fixture.posted)
            assertEquals(
                listOf<Pair<String, StoreWriteResponse.Success>>(
                    "A" to StoreWriteResponse.Success.Typed("ack:B"),
                    "B" to StoreWriteResponse.Success.Typed("ack:B"),
                ),
                fixture.successes,
            )
            assertNull(fixture.bookkeeper.getLastFailedSync("key"))
        }

        verifyOrder(Long.MAX_VALUE, Long.MIN_VALUE)
        verifyOrder(7L, 7L)
    }

    @Test
    fun reusedRequestHasDistinctAdmissions() = runTest {
        var attempt = 0
        val fixture = MutableStoreRaceFixture(
            scope = this,
            post = { _, value ->
                if (++attempt == 1) {
                    UpdaterResult.Error.Message("offline:$value")
                } else {
                    UpdaterResult.Success.Typed("ack:$value")
                }
            },
        )
        val request = fixture.request("A", 1L, id = "same")

        assertIs<StoreWriteResponse.Error>(fixture.store.write(request))
        assertEquals(StoreWriteResponse.Success.Typed("ack:A"), fixture.store.write(request))

        assertEquals(listOf("key" to "A", "key" to "A"), fixture.localWrites)
        assertEquals(listOf("key" to "A", "key" to "A"), fixture.posted)
        assertEquals(
            listOf<Pair<String, StoreWriteResponse.Success>>(
                "same" to StoreWriteResponse.Success.Typed("ack:A"),
                "same" to StoreWriteResponse.Success.Typed("ack:A"),
            ),
            fixture.successes,
        )
        assertEquals(2, fixture.updaterSuccesses.size)
    }

    @Test
    fun coalescedWaitingCallerReturnsSavedSuccess() = runTest {
        val aPost = RaceGate()
        val bLocalReturn = RaceGate()
        val cPersisted = CompletableDeferred<Unit>()
        val fixture = MutableStoreRaceFixture(
            scope = this,
            post = { _, value ->
                if (value == "A") aPost.pause()
                UpdaterResult.Success.Typed("ack:$value")
            },
            beforeLocalReturn = { _, value ->
                when (value) {
                    "B" -> bLocalReturn.pause()
                    "C" -> cPersisted.complete(Unit)
                }
            },
        )

        val a = async { fixture.store.write(fixture.request("A", 1L)) }
        aPost.entered.await()
        val b = async { fixture.store.write(fixture.request("B", 2L)) }
        bLocalReturn.entered.await()
        val c = async { fixture.store.write(fixture.request("C", 3L)) }
        runCurrent()
        assertEquals(listOf("key" to "A", "key" to "B"), fixture.localWrites)

        bLocalReturn.open()
        cPersisted.await()
        assertEquals(listOf("key" to "A"), fixture.posted)
        aPost.open()

        assertEquals(StoreWriteResponse.Success.Typed("ack:A"), a.await())
        assertEquals(StoreWriteResponse.Success.Typed("ack:C"), b.await())
        assertEquals(StoreWriteResponse.Success.Typed("ack:C"), c.await())
        assertEquals(listOf("key" to "A", "key" to "C"), fixture.posted)
        assertEquals(
            listOf<Pair<String, StoreWriteResponse.Success>>(
                "A" to StoreWriteResponse.Success.Typed("ack:A"),
                "B" to StoreWriteResponse.Success.Typed("ack:C"),
                "C" to StoreWriteResponse.Success.Typed("ack:C"),
            ),
            fixture.successes,
        )
    }

    @Test
    fun sameKeyPostsSerializeWithoutBlockingLocalWrites() = runTest {
        val aPost = RaceGate()
        val bPersisted = CompletableDeferred<Unit>()
        val fixture = MutableStoreRaceFixture(
            scope = this,
            post = { _, value ->
                if (value == "A") aPost.pause()
                UpdaterResult.Success.Typed("ack:$value")
            },
            beforeLocalReturn = { _, value ->
                if (value == "B") bPersisted.complete(Unit)
            },
        )

        val a = async { fixture.store.write(fixture.request("A", 1L)) }
        aPost.entered.await()
        val b = async { fixture.store.write(fixture.request("B", 2L)) }
        bPersisted.await()

        assertEquals("B", fixture.local.value["key"])
        assertEquals(listOf("key" to "A"), fixture.posted)
        assertEquals(1, fixture.maximumActivePosts["key"])

        aPost.open()
        assertIs<StoreWriteResponse.Success>(a.await())
        assertIs<StoreWriteResponse.Success>(b.await())
        assertEquals(listOf("key" to "A", "key" to "B"), fixture.posted)
        assertEquals(1, fixture.maximumActivePosts["key"])
    }

    @Test
    fun eagerRetryRechecksAfterExplicitSuccess() = runTest {
        val explicitA = RaceGate()
        val fixture = MutableStoreRaceFixture(
            scope = this,
            post = { _, value ->
                explicitA.pause()
                UpdaterResult.Success.Typed("ack:$value")
            },
        )

        val writer = async { fixture.store.write(fixture.request("A", 1L)) }
        explicitA.entered.await()
        val reader = async {
            fixture.store.stream<String>(StoreReadRequest.localOnly("key"))
                .first { it is StoreReadResponse.Data }
        }
        runCurrent()
        assertEquals(listOf("key" to "A"), fixture.posted)

        explicitA.open()
        assertIs<StoreWriteResponse.Success>(writer.await())
        reader.await()
        assertEquals(listOf("key" to "A"), fixture.posted)
    }

    @Test
    fun differentKeysMakeProgressIndependently() = runTest {
        val kPost = RaceGate()
        val fixture = MutableStoreRaceFixture(
            scope = this,
            post = { key, value ->
                if (key == "K") kPost.pause()
                UpdaterResult.Success.Typed("ack:$value")
            },
        )

        val k = async { fixture.store.write(fixture.request("value-K", 1L, key = "K")) }
        kPost.entered.await()
        val jResponse = fixture.store.write(fixture.request("value-J", 2L, key = "J"))

        assertEquals(StoreWriteResponse.Success.Typed("ack:value-J"), jResponse)
        assertEquals("value-J", fixture.local.value["J"])
        assertEquals("value-J", fixture.remote["J"])
        assertEquals(listOf("K" to "value-K", "J" to "value-J"), fixture.posted)

        kPost.open()
        assertIs<StoreWriteResponse.Success>(k.await())
        assertEquals("value-K", fixture.remote["K"])
    }

    @Test
    fun markerOnlyRetryUsesLocalValue() = runTest {
        val fixture = MutableStoreRaceFixture(
            scope = this,
            post = { _, value -> UpdaterResult.Success.Typed("ack:$value") },
        )
        fixture.local.value = mapOf("key" to "A")
        fixture.bookkeeper.setLastFailedSync("key", 1L)

        fixture.store.stream<String>(StoreReadRequest.localOnly("key"))
            .first { it is StoreReadResponse.Data }

        assertEquals(listOf("key" to "A"), fixture.posted)
        assertEquals("A", fixture.remote["key"])
        assertNull(fixture.bookkeeper.getLastFailedSync("key"))
        assertEquals(emptyList(), fixture.successes)
        assertEquals(emptyList(), fixture.updaterSuccesses)
    }

    @Test
    fun eagerRetryUsesLatestLocalValueWithPendingWrites() = runTest {
        val fixture = MutableStoreRaceFixture(
            scope = this,
            post = { _, value ->
                if (value == "A") {
                    UpdaterResult.Error.Message("offline:A")
                } else {
                    UpdaterResult.Success.Typed("ack:$value")
                }
            },
        )
        assertIs<StoreWriteResponse.Error>(fixture.store.write(fixture.request("A", 1L)))
        fixture.local.value = mapOf("key" to "B")
        fixture.cache.invalidate("key")

        fixture.store.stream<String>(StoreReadRequest.localOnly("key"))
            .first { it is StoreReadResponse.Data }

        assertEquals(listOf("key" to "A", "key" to "B"), fixture.posted)
        assertEquals("B", fixture.remote["key"])
        assertEquals(
            listOf<Pair<String, StoreWriteResponse.Success>>(
                "A" to StoreWriteResponse.Success.Typed("ack:B"),
            ),
            fixture.successes,
        )
        assertNull(fixture.bookkeeper.getLastFailedSync("key"))
    }

    @Test
    fun noBookkeeperPreservesExistingEagerBehavior() = runTest {
        val fixture = MutableStoreRaceFixture(
            scope = this,
            post = { _, value ->
                if (value == "A") {
                    UpdaterResult.Error.Message("offline:A")
                } else {
                    UpdaterResult.Success.Typed("ack:$value")
                }
            },
            withBookkeeper = false,
        )
        assertIs<StoreWriteResponse.Error>(fixture.store.write(fixture.request("A", 1L)))

        fixture.store.stream<String>(StoreReadRequest.localOnly("key"))
            .first { it is StoreReadResponse.Data }
        assertEquals(listOf("key" to "A"), fixture.posted)

        assertEquals(
            StoreWriteResponse.Success.Typed("ack:B"),
            fixture.store.write(fixture.request("B", 2L)),
        )
        assertEquals(listOf("key" to "A", "key" to "B"), fixture.posted)
        assertEquals(
            listOf<Pair<String, StoreWriteResponse.Success>>(
                "A" to StoreWriteResponse.Success.Typed("ack:B"),
                "B" to StoreWriteResponse.Success.Typed("ack:B"),
            ),
            fixture.successes,
        )
    }
}
