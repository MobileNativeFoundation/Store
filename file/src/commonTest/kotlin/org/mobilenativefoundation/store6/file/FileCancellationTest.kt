@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileCancellationTest {
    @Test
    fun preAdmissionCancellation_writeAppliesNothingAndPublishesNoSignal() =
        runTest {
            withFreshDirectory("store6-file-cancellation-write-before-admission") { directory ->
                val parked = CompletableDeferred<Unit>()
                val never = CompletableDeferred<Unit>()
                val source =
                    FileSourceOfTruth<FileKitKey, String>(
                        directory = directory,
                        codec = Utf8StringFileCodec,
                        corruptionPolicy = FileCorruptionPolicy.QUARANTINE,
                        ioContext = coroutineContext,
                        beforeAdmissionTestGate = {
                            parked.complete(Unit)
                            never.await()
                        },
                    )
                val key = cancellationKey("write-before-admission")
                val mutationScope: CoroutineScope = this

                source.reader(key).test {
                    assertNull(
                        awaitItem(),
                        "The active reader must start with the absent row",
                    )
                    val child = mutationScope.launch { source.write(key, "value") }
                    parked.await()

                    child.cancel()
                    child.join()

                    assertTrue(
                        child.isCancelled,
                        "Cancellation before admission must cancel the write caller",
                    )
                    expectNoEvents()
                    assertNull(
                        freshRead(directory, key),
                        "Cancellation before admission must leave the canonical row absent",
                    )
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

    @Test
    fun preAdmissionCancellation_deleteAppliesNothingAndPublishesNoSignal() =
        runTest {
            withFreshDirectory("store6-file-cancellation-delete-before-admission") { directory ->
                val key = cancellationKey("delete-before-admission")
                FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                    .write(key, "value")
                val parked = CompletableDeferred<Unit>()
                val never = CompletableDeferred<Unit>()
                val source =
                    FileSourceOfTruth<FileKitKey, String>(
                        directory = directory,
                        codec = Utf8StringFileCodec,
                        corruptionPolicy = FileCorruptionPolicy.QUARANTINE,
                        ioContext = coroutineContext,
                        beforeAdmissionTestGate = {
                            parked.complete(Unit)
                            never.await()
                        },
                    )
                val mutationScope: CoroutineScope = this

                source.reader(key).test {
                    assertEquals(
                        "value",
                        awaitItem(),
                        "The active reader must start with the persisted row",
                    )
                    val child = mutationScope.launch { source.delete(key) }
                    parked.await()

                    child.cancel()
                    child.join()

                    assertTrue(
                        child.isCancelled,
                        "Cancellation before admission must cancel the delete caller",
                    )
                    expectNoEvents()
                    assertEquals(
                        "value",
                        freshRead(directory, key),
                        "Cancellation before admission must leave the canonical row readable",
                    )
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

    @Test
    fun postAdmissionCancellation_writeCompletesNotifiesAndReturnsNormally() =
        runTest {
            withFreshDirectory("store6-file-cancellation-write-after-admission") { directory ->
                val parked = CompletableDeferred<Unit>()
                val resume = CompletableDeferred<Unit>()
                val source =
                    FileSourceOfTruth<FileKitKey, String>(
                        directory = directory,
                        codec = Utf8StringFileCodec,
                        corruptionPolicy = FileCorruptionPolicy.QUARANTINE,
                        ioContext = coroutineContext,
                        afterAdmissionTestGate = {
                            parked.complete(Unit)
                            resume.await()
                        },
                    )
                val key = cancellationKey("write-after-admission")
                val mutationScope: CoroutineScope = this
                var returnedNormally = false

                source.reader(key).test {
                    assertNull(
                        awaitItem(),
                        "The active reader must start with the absent row",
                    )
                    val child =
                        mutationScope.launch {
                            source.write(key, "value")
                            returnedNormally = true
                        }
                    parked.await()

                    child.cancel()
                    resume.complete(Unit)
                    child.join()

                    assertTrue(
                        returnedNormally,
                        "A write cancelled after admission must return normally from the mutation boundary",
                    )
                    assertTrue(
                        child.isCancelled,
                        "The write caller must retain cancellation after the admitted mutation returns",
                    )
                    assertEquals(
                        "value",
                        awaitItem(),
                        "An admitted write must notify its active reader before returning",
                    )
                    assertEquals(
                        "value",
                        freshRead(directory, key),
                        "An admitted write must durably apply the canonical row",
                    )
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

    @Test
    fun postAdmissionCancellation_deleteCompletesNotifiesAndReturnsNormally() =
        runTest {
            withFreshDirectory("store6-file-cancellation-delete-after-admission") { directory ->
                val key = cancellationKey("delete-after-admission")
                FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
                    .write(key, "value")
                val parked = CompletableDeferred<Unit>()
                val resume = CompletableDeferred<Unit>()
                val source =
                    FileSourceOfTruth<FileKitKey, String>(
                        directory = directory,
                        codec = Utf8StringFileCodec,
                        corruptionPolicy = FileCorruptionPolicy.QUARANTINE,
                        ioContext = coroutineContext,
                        afterAdmissionTestGate = {
                            parked.complete(Unit)
                            resume.await()
                        },
                    )
                val mutationScope: CoroutineScope = this
                var returnedNormally = false

                source.reader(key).test {
                    assertEquals(
                        "value",
                        awaitItem(),
                        "The active reader must start with the persisted row",
                    )
                    val child =
                        mutationScope.launch {
                            source.delete(key)
                            returnedNormally = true
                        }
                    parked.await()

                    child.cancel()
                    resume.complete(Unit)
                    child.join()

                    assertTrue(
                        returnedNormally,
                        "A delete cancelled after admission must return normally from the mutation boundary",
                    )
                    assertTrue(
                        child.isCancelled,
                        "The delete caller must retain cancellation after the admitted mutation returns",
                    )
                    assertNull(
                        awaitItem(),
                        "An admitted delete must notify its active reader of absence before returning",
                    )
                    assertNull(
                        freshRead(directory, key),
                        "An admitted delete must durably remove the canonical row",
                    )
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
}

private fun cancellationKey(id: String): FileKitKey =
    FileKitKey(
        namespace = StoreNamespace("cancellation"),
        id = id,
    )

private suspend fun freshRead(
    directory: kotlinx.io.files.Path,
    key: FileKitKey,
): String? =
    FileSourceOfTruth<FileKitKey, String>(directory, Utf8StringFileCodec)
        .reader(key)
        .first()
