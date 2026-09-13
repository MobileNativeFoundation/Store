package org.mobilenativefoundation.store6.core.internal

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.FakeWallClock
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.SingleRowTestSourceOfTruth
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.TestKey
import org.mobilenativefoundation.store6.core.seam.Overlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)
class ProjectionAuthorizationHandoffTest {
    @Test
    fun mappingBeforeConfirmFresh_deliveryBeforeConfirmFresh_retirementBeforeConfirmFresh_successorAbsent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = true,
            successorPresent = false,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingBeforeConfirmFresh_deliveryBeforeConfirmFresh_retirementBeforeConfirmFresh_successorAbsent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = true,
            successorPresent = false,
            invalidationInterleaved = true,
        )

    @Test
    fun mappingBeforeConfirmFresh_deliveryBeforeConfirmFresh_retirementBeforeConfirmFresh_successorPresent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = true,
            successorPresent = true,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingBeforeConfirmFresh_deliveryBeforeConfirmFresh_retirementBeforeConfirmFresh_successorPresent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = true,
            successorPresent = true,
            invalidationInterleaved = true,
        )

    @Test
    fun mappingBeforeConfirmFresh_deliveryBeforeConfirmFresh_retirementAfterConfirmFresh_successorAbsent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = false,
            successorPresent = false,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingBeforeConfirmFresh_deliveryBeforeConfirmFresh_retirementAfterConfirmFresh_successorAbsent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = false,
            successorPresent = false,
            invalidationInterleaved = true,
        )

    @Test
    fun mappingBeforeConfirmFresh_deliveryBeforeConfirmFresh_retirementAfterConfirmFresh_successorPresent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = false,
            successorPresent = true,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingBeforeConfirmFresh_deliveryBeforeConfirmFresh_retirementAfterConfirmFresh_successorPresent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = false,
            successorPresent = true,
            invalidationInterleaved = true,
        )

    @Test
    fun mappingBeforeConfirmFresh_deliveryAfterConfirmFresh_retirementBeforeConfirmFresh_successorAbsent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = true,
            successorPresent = false,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingBeforeConfirmFresh_deliveryAfterConfirmFresh_retirementBeforeConfirmFresh_successorAbsent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = true,
            successorPresent = false,
            invalidationInterleaved = true,
        )

    @Test
    fun mappingBeforeConfirmFresh_deliveryAfterConfirmFresh_retirementBeforeConfirmFresh_successorPresent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = true,
            successorPresent = true,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingBeforeConfirmFresh_deliveryAfterConfirmFresh_retirementBeforeConfirmFresh_successorPresent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = true,
            successorPresent = true,
            invalidationInterleaved = true,
        )

    @Test
    fun mappingBeforeConfirmFresh_deliveryAfterConfirmFresh_retirementAfterConfirmFresh_successorAbsent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = false,
            successorPresent = false,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingBeforeConfirmFresh_deliveryAfterConfirmFresh_retirementAfterConfirmFresh_successorAbsent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = false,
            successorPresent = false,
            invalidationInterleaved = true,
        )

    @Test
    fun mappingBeforeConfirmFresh_deliveryAfterConfirmFresh_retirementAfterConfirmFresh_successorPresent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = false,
            successorPresent = true,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingBeforeConfirmFresh_deliveryAfterConfirmFresh_retirementAfterConfirmFresh_successorPresent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = true,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = false,
            successorPresent = true,
            invalidationInterleaved = true,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryBeforeConfirmFresh_retirementBeforeConfirmFresh_successorAbsent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = true,
            successorPresent = false,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryBeforeConfirmFresh_retirementBeforeConfirmFresh_successorAbsent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = true,
            successorPresent = false,
            invalidationInterleaved = true,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryBeforeConfirmFresh_retirementBeforeConfirmFresh_successorPresent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = true,
            successorPresent = true,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryBeforeConfirmFresh_retirementBeforeConfirmFresh_successorPresent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = true,
            successorPresent = true,
            invalidationInterleaved = true,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryBeforeConfirmFresh_retirementAfterConfirmFresh_successorAbsent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = false,
            successorPresent = false,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryBeforeConfirmFresh_retirementAfterConfirmFresh_successorAbsent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = false,
            successorPresent = false,
            invalidationInterleaved = true,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryBeforeConfirmFresh_retirementAfterConfirmFresh_successorPresent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = false,
            successorPresent = true,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryBeforeConfirmFresh_retirementAfterConfirmFresh_successorPresent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = true,
            retirementBeforeConfirmFresh = false,
            successorPresent = true,
            invalidationInterleaved = true,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryAfterConfirmFresh_retirementBeforeConfirmFresh_successorAbsent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = true,
            successorPresent = false,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryAfterConfirmFresh_retirementBeforeConfirmFresh_successorAbsent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = true,
            successorPresent = false,
            invalidationInterleaved = true,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryAfterConfirmFresh_retirementBeforeConfirmFresh_successorPresent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = true,
            successorPresent = true,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryAfterConfirmFresh_retirementBeforeConfirmFresh_successorPresent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = true,
            successorPresent = true,
            invalidationInterleaved = true,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryAfterConfirmFresh_retirementAfterConfirmFresh_successorAbsent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = false,
            successorPresent = false,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryAfterConfirmFresh_retirementAfterConfirmFresh_successorAbsent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = false,
            successorPresent = false,
            invalidationInterleaved = true,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryAfterConfirmFresh_retirementAfterConfirmFresh_successorPresent_invalidationAbsent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = false,
            successorPresent = true,
            invalidationInterleaved = false,
        )

    @Test
    fun mappingAfterConfirmFresh_deliveryAfterConfirmFresh_retirementAfterConfirmFresh_successorPresent_invalidationPresent() =
        runMatrixCell(
            mappingBeforeConfirmFresh = false,
            deliveryBeforeConfirmFresh = false,
            retirementBeforeConfirmFresh = false,
            successorPresent = true,
            invalidationInterleaved = true,
        )

    private fun runMatrixCell(
        mappingBeforeConfirmFresh: Boolean,
        deliveryBeforeConfirmFresh: Boolean,
        retirementBeforeConfirmFresh: Boolean,
        successorPresent: Boolean,
        invalidationInterleaved: Boolean,
    ) = runTest {
        val harness = matrixHarness("projection-authorization-matrix")
        assertEquals("seed", harness.engine.get(Freshness.LocalOnly))

        turbineScope {
            val observer = harness.engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            try {
                settleInitialProjection(harness, observer)

                val firstMapping = harness.mappingGate.gateNext()
                val firstDelivery = harness.readerDeliveryGate.gateNext()
                val firstWrite = harness.sourceOfTruth.gateNextWrite()
                val firstResidenceProjection = harness.afterProjectionDeliveryGate.gateNext()
                harness.overlay.clearCalls()
                val apply =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        harness.engine.applyWrite("echo")
                    }

                firstMapping.entered.await()
                firstWrite.published.await()
                assertFalse(firstDelivery.entered.isCompleted)

                firstWrite.releaseReturn.complete(Unit)
                apply.await()
                assertProjectionCall(
                    call = harness.overlay.awaitCall(),
                    retired = false,
                )
                firstResidenceProjection.entered.await()
                firstResidenceProjection.releaseAndAwait()
                testScheduler.runCurrent()
                observer.expectNoEvents()

                val invalidateBeforePredecessorDelivery =
                    invalidationInterleaved &&
                        mappingBeforeConfirmFresh &&
                        deliveryBeforeConfirmFresh
                if (invalidateBeforePredecessorDelivery) {
                    assertTrue(firstMapping.entered.isCompleted, "raw row was captured")
                    assertFalse(firstDelivery.entered.isCompleted)
                    harness.engine.invalidate()
                    testScheduler.runCurrent()
                    observer.expectNoEvents()
                }

                if (mappingBeforeConfirmFresh) {
                    firstMapping.releaseAndAwait()
                    testScheduler.runCurrent()
                    assertTrue(
                        firstDelivery.entered.isCompleted,
                        "mapping-before must make the first delivery gate causally reachable",
                    )
                } else {
                    assertFalse(firstMapping.exited.isCompleted)
                }

                if (deliveryBeforeConfirmFresh) {
                    if (mappingBeforeConfirmFresh) {
                        firstDelivery.entered.await()
                        assertTrue(firstDelivery.entered.isCompleted)
                        firstDelivery.releaseAndAwait()
                        testScheduler.runCurrent()
                    } else {
                        firstDelivery.requestRelease()
                        testScheduler.runCurrent()
                        assertFalse(
                            firstDelivery.entered.isCompleted,
                            "delivery cannot enter while its upstream mapping is held",
                        )
                    }
                } else if (mappingBeforeConfirmFresh) {
                    firstDelivery.entered.await()
                    assertFalse(firstDelivery.exited.isCompleted)
                }

                if (retirementBeforeConfirmFresh) {
                    harness.overlay.clearCalls()
                    harness.overlay.retire(harness.key)
                    assertProjectionCall(
                        call = harness.overlay.awaitCall(),
                        retired = true,
                    )
                    testScheduler.runCurrent()
                    if (mappingBeforeConfirmFresh && deliveryBeforeConfirmFresh) {
                        assertPredecessorConfirmed(observer)
                    } else {
                        observer.expectNoEvents()
                    }
                } else {
                    observer.expectNoEvents()
                }

                if (invalidationInterleaved && !invalidateBeforePredecessorDelivery) {
                    assertTrue(firstMapping.entered.isCompleted, "raw row was captured")
                    assertFalse(
                        firstDelivery.exited.isCompleted,
                        "invalidation must remain between raw capture and collector delivery",
                    )
                    // Do not advance the scheduler before confirmFresh: the stale-epoch observer is
                    // itself a delivery path and must resolve the post-confirmFresh residence.
                    harness.engine.invalidate()
                }
                val heldR2 =
                    if (successorPresent) {
                        harness.beforeProjectionDeliveryGate.gateNext()
                    } else {
                        null
                    }
                harness.engine.confirmFresh(etag = "confirmed")
                testScheduler.runCurrent()

                if (!mappingBeforeConfirmFresh) {
                    firstMapping.releaseAndAwait()
                    testScheduler.runCurrent()
                    firstDelivery.entered.await()
                    assertTrue(
                        firstDelivery.entered.isCompleted,
                        "mapping release after confirmFresh must make delivery reachable",
                    )
                }

                if (!deliveryBeforeConfirmFresh) {
                    firstDelivery.entered.await()
                    assertFalse(firstDelivery.exited.isCompleted)
                    firstDelivery.releaseAndAwait()
                } else if (!mappingBeforeConfirmFresh) {
                    firstDelivery.exited.await()
                }
                testScheduler.runCurrent()

                if (successorPresent) {
                    runSuccessorCell(
                        harness = harness,
                        observer = observer,
                        heldR2 = checkNotNull(heldR2),
                        retirementBeforeConfirmFresh = retirementBeforeConfirmFresh,
                        predecessorDeliveredBeforeConfirm =
                            mappingBeforeConfirmFresh && deliveryBeforeConfirmFresh,
                    )
                } else {
                    finishWithoutSuccessor(
                        harness = harness,
                        observer = observer,
                        retirementBeforeConfirmFresh = retirementBeforeConfirmFresh,
                    )
                }
            } finally {
                harness.releaseAll()
                observer.cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun consecutiveConfirmFresh_coalescedReadyPreservesStableAuthorizationLineage() = runTest {
        val harness = matrixHarness("projection-authorization-consecutive-confirm")
        assertEquals("seed", harness.engine.get(Freshness.LocalOnly))

        turbineScope {
            val observer = harness.engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            try {
                settleInitialProjection(harness, observer)
                authorizeR1(harness, observer)

                harness.overlay.clearCalls()
                val heldR2 = harness.beforeProjectionDeliveryGate.gateNext()
                harness.engine.confirmFresh(etag = "r2")
                heldR2.entered.await()
                assertProjectionCall(harness.overlay.awaitCall(), retired = false)

                harness.overlay.clearCalls()
                harness.engine.confirmFresh(etag = "r3")
                assertProjectionCall(harness.overlay.awaitCall(), retired = false)
                testScheduler.runCurrent()

                val heldR3 = harness.beforeProjectionDeliveryGate.gateNext()
                val staleR2Delivered = harness.afterProjectionDeliveryGate.gateNext()
                heldR2.releaseAndAwait()
                staleR2Delivered.entered.await()
                observer.expectNoEvents()
                staleR2Delivered.releaseAndAwait()

                heldR3.entered.await()
                val currentR3Delivered = harness.afterProjectionDeliveryGate.gateNext()
                heldR3.releaseAndAwait()
                currentR3Delivered.entered.await()
                observer.expectNoEvents()
                currentR3Delivered.releaseAndAwait()

                val retirementDelivered = harness.afterProjectionDeliveryGate.gateNext()
                harness.overlay.clearCalls()
                harness.overlay.retire(harness.key)
                assertProjectionCall(harness.overlay.awaitCall(), retired = true)
                retirementDelivered.entered.await()
                assertLatestConfirmed(observer)
                retirementDelivered.releaseAndAwait()
            } finally {
                harness.releaseAll()
                observer.cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun readerAwaitingObsoleteR1Readiness_handsAuthorizationToConfirmFreshR2() = runTest {
        val harness = matrixHarness("projection-authorization-obsolete-readiness")
        assertEquals("seed", harness.engine.get(Freshness.LocalOnly))

        turbineScope {
            val observer = harness.engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            try {
                settleInitialProjection(harness, observer)

                val r1Mapping = harness.mappingGate.gateNext()
                val r1Write = harness.sourceOfTruth.gateNextWrite()
                val heldR1Apply = harness.beforeProjectionApplyGate.gateNext()
                harness.overlay.clearCalls()
                val apply =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        harness.engine.applyWrite("echo")
                    }
                r1Mapping.entered.await()
                r1Write.published.await()
                r1Write.releaseReturn.complete(Unit)
                apply.await()
                heldR1Apply.entered.await()

                val heldReadiness = harness.projectionReadinessGate.gateNext()
                r1Mapping.releaseAndAwait()
                heldReadiness.entered.await()
                harness.engine.confirmFresh(etag = "r2")
                heldReadiness.releaseAndAwait()
                testScheduler.runCurrent()
                observer.expectNoEvents()

                val r2Delivered = harness.afterProjectionDeliveryGate.gateNext()
                heldR1Apply.releaseAndAwait()
                assertProjectionCall(harness.overlay.awaitCall(), retired = false)
                assertProjectionCall(harness.overlay.awaitCall(), retired = false)
                r2Delivered.entered.await()
                observer.expectNoEvents()
                r2Delivered.releaseAndAwait()

                val retirementDelivered = harness.afterProjectionDeliveryGate.gateNext()
                harness.overlay.clearCalls()
                harness.overlay.retire(harness.key)
                assertProjectionCall(harness.overlay.awaitCall(), retired = true)
                retirementDelivered.entered.await()
                assertLatestConfirmed(observer)
                retirementDelivered.releaseAndAwait()
            } finally {
                harness.releaseAll()
                observer.cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun confirmFreshBetweenResolutionAndAuthorization_handsCapturedR1LineageToR2() = runTest {
        val harness = matrixHarness("projection-authorization-resolve-factory-race")
        assertEquals("seed", harness.engine.get(Freshness.LocalOnly))

        turbineScope {
            val observer = harness.engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
            try {
                settleInitialProjection(harness, observer)

                val mapping = harness.mappingGate.gateNext()
                val delivery = harness.readerDeliveryGate.gateNext()
                val write = harness.sourceOfTruth.gateNextWrite()
                val residenceProjection = harness.afterProjectionDeliveryGate.gateNext()
                harness.overlay.clearCalls()
                val apply =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        harness.engine.applyWrite("echo")
                    }
                mapping.entered.await()
                write.published.await()
                write.releaseReturn.complete(Unit)
                apply.await()
                assertProjectionCall(harness.overlay.awaitCall(), retired = false)
                residenceProjection.entered.await()
                residenceProjection.releaseAndAwait()

                val authorization = harness.projectionAuthorizationGate.gateNext()
                mapping.releaseAndAwait()
                delivery.entered.await()
                delivery.releaseAndAwait()
                authorization.entered.await()

                harness.engine.confirmFresh(etag = "r2")
                authorization.releaseAndAwait()
                testScheduler.runCurrent()
                observer.expectNoEvents()

                val retirementDelivered = harness.afterProjectionDeliveryGate.gateNext()
                harness.overlay.clearCalls()
                harness.overlay.retire(harness.key)
                assertProjectionCall(harness.overlay.awaitCall(), retired = true)
                retirementDelivered.entered.await()
                assertLatestConfirmed(observer)
                retirementDelivered.releaseAndAwait()
            } finally {
                harness.releaseAll()
                observer.cancelAndIgnoreRemainingEvents()
            }
        }
    }

    private suspend fun TestScope.runSuccessorCell(
        harness: MatrixHarness,
        observer: ReceiveTurbine<StoreResult<String>>,
        heldR2: SequencedGate.Step,
        retirementBeforeConfirmFresh: Boolean,
        predecessorDeliveredBeforeConfirm: Boolean,
    ) {
        heldR2.entered.await()
        testScheduler.runCurrent()
        if (retirementBeforeConfirmFresh && !predecessorDeliveredBeforeConfirm) {
            assertPredecessorConfirmed(observer)
        } else {
            observer.expectNoEvents()
        }

        val successorMapping = harness.mappingGate.gateNext()
        val successorDelivery = harness.readerDeliveryGate.gateNext()
        val successorWrite = harness.sourceOfTruth.gateNextWrite()
        harness.overlay.clearCalls()
        val successor =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                harness.engine.applyWrite("echo")
            }
        successorMapping.entered.await()
        successorWrite.published.await()
        successorWrite.releaseReturn.complete(Unit)
        successor.await()
        assertProjectionCall(
            call = harness.overlay.awaitCall(),
            retired = retirementBeforeConfirmFresh,
        )

        if (!retirementBeforeConfirmFresh) {
            harness.overlay.clearCalls()
            harness.overlay.retire(harness.key)
            assertProjectionCall(harness.overlay.awaitCall(), retired = true)
        }
        testScheduler.runCurrent()
        assertFalse(successorMapping.exited.isCompleted)
        assertFalse(successorDelivery.entered.isCompleted)

        val heldR3 = harness.beforeProjectionDeliveryGate.gateNext()
        val staleR2Delivered = harness.afterProjectionDeliveryGate.gateNext()
        heldR2.releaseAndAwait()
        staleR2Delivered.entered.await()
        observer.expectNoEvents()
        staleR2Delivered.releaseAndAwait()

        heldR3.entered.await()
        val unauthorizedR3Delivered = harness.afterProjectionDeliveryGate.gateNext()
        heldR3.releaseAndAwait()
        unauthorizedR3Delivered.entered.await()
        observer.expectNoEvents()
        unauthorizedR3Delivered.releaseAndAwait()

        assertFalse(successorDelivery.entered.isCompleted)
        successorMapping.releaseAndAwait()
        testScheduler.runCurrent()
        successorDelivery.entered.await()
        observer.expectNoEvents()
        successorDelivery.releaseAndAwait()
        testScheduler.runCurrent()
        assertLatestConfirmed(observer)
    }

    private suspend fun TestScope.finishWithoutSuccessor(
        harness: MatrixHarness,
        observer: ReceiveTurbine<StoreResult<String>>,
        retirementBeforeConfirmFresh: Boolean,
    ) {
        if (!retirementBeforeConfirmFresh) {
            observer.expectNoEvents()
            val retirementDelivered = harness.afterProjectionDeliveryGate.gateNext()
            harness.overlay.clearCalls()
            harness.overlay.retire(harness.key)
            assertProjectionCall(harness.overlay.awaitCall(), retired = true)
            retirementDelivered.entered.await()
            assertLatestConfirmed(observer)
            retirementDelivered.releaseAndAwait()
        } else {
            testScheduler.runCurrent()
            assertLatestConfirmed(observer)
        }
    }

    private suspend fun TestScope.authorizeR1(
        harness: MatrixHarness,
        observer: ReceiveTurbine<StoreResult<String>>,
    ) {
        val mapping = harness.mappingGate.gateNext()
        val delivery = harness.readerDeliveryGate.gateNext()
        val write = harness.sourceOfTruth.gateNextWrite()
        val residenceProjection = harness.afterProjectionDeliveryGate.gateNext()
        harness.overlay.clearCalls()
        val apply =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                harness.engine.applyWrite("echo")
            }
        mapping.entered.await()
        write.published.await()
        write.releaseReturn.complete(Unit)
        apply.await()
        assertProjectionCall(harness.overlay.awaitCall(), retired = false)
        residenceProjection.entered.await()
        residenceProjection.releaseAndAwait()
        mapping.releaseAndAwait()
        delivery.entered.await()
        delivery.releaseAndAwait()
        testScheduler.runCurrent()
        observer.expectNoEvents()
    }

    private suspend fun TestScope.settleInitialProjection(
        harness: MatrixHarness,
        observer: ReceiveTurbine<StoreResult<String>>,
    ) {
        testScheduler.runCurrent()
        val initial = assertIs<StoreResult.Data<String>>(observer.expectMostRecentItem())
        assertEquals("optimistic", initial.value)
        assertEquals(Origin.OVERLAY, initial.origin)
        harness.sourceOfTruth.liveReaderStarted.await()
        testScheduler.runCurrent()
        harness.overlay.clearCalls()
        observer.expectNoEvents()
    }

    private fun TestScope.matrixHarness(id: String): MatrixHarness {
        val key = TestKey(id)
        val sourceOfTruth = MatrixSourceOfTruth()
        val overlay = MatrixOverlay()
        val mappingGate = SequencedGate()
        val readerDeliveryGate = SequencedGate()
        val beforeProjectionApplyGate = SequencedGate()
        val beforeProjectionDeliveryGate = SequencedGate()
        val afterProjectionDeliveryGate = SequencedGate()
        val projectionReadinessGate = SequencedGate()
        val projectionAuthorizationGate = SequencedGate()
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    error("projection authorization matrix must never fetch")
                },
                sot = sourceOfTruth,
                bookkeeper = InMemoryBookkeeper(),
                validator = DefaultFreshnessValidator,
                wallClock = FakeWallClock(now = 0L),
                engineScope = backgroundScope,
                beforeReaderRecordMappingTestGate = mappingGate::awaitIfQueued,
                beforeReaderDeliveryTestGate = readerDeliveryGate::awaitIfQueued,
                overlay = overlay,
                beforeProjectionApplyTestGate = { beforeProjectionApplyGate.awaitIfQueued() },
                beforeProjectionDeliveryLockTestGate =
                    beforeProjectionDeliveryGate::awaitIfQueued,
                afterProjectionDeliveryTestGate = afterProjectionDeliveryGate::awaitIfQueued,
                beforeProjectionReadinessWaitTestGate =
                    projectionReadinessGate::awaitIfQueued,
                beforeProjectionAuthorizationTestGate =
                    projectionAuthorizationGate::awaitIfQueued,
            )
        return MatrixHarness(
            key = key,
            engine = engine,
            sourceOfTruth = sourceOfTruth,
            overlay = overlay,
            mappingGate = mappingGate,
            readerDeliveryGate = readerDeliveryGate,
            beforeProjectionApplyGate = beforeProjectionApplyGate,
            beforeProjectionDeliveryGate = beforeProjectionDeliveryGate,
            afterProjectionDeliveryGate = afterProjectionDeliveryGate,
            projectionReadinessGate = projectionReadinessGate,
            projectionAuthorizationGate = projectionAuthorizationGate,
        )
    }

    private fun assertProjectionCall(
        call: MatrixOverlay.ApplyCall,
        retired: Boolean,
    ) {
        assertEquals("echo", call.base)
        assertEquals(retired, call.retired)
    }

    private fun assertPredecessorConfirmed(
        observer: ReceiveTurbine<StoreResult<String>>,
    ) {
        val data = assertIs<StoreResult.Data<String>>(observer.expectMostRecentItem())
        assertEquals("echo", data.value)
        assertEquals(Origin.SOT, data.origin)
    }

    private fun assertLatestConfirmed(
        observer: ReceiveTurbine<StoreResult<String>>,
    ): StoreResult.Data<String> {
        val data = assertIs<StoreResult.Data<String>>(observer.expectMostRecentItem())
        assertEquals("echo", data.value)
        assertEquals(Origin.SOT, data.origin)
        assertFalse(data.isStale)
        assertFalse(data.refreshing)
        return data
    }

    private class MatrixHarness(
        val key: TestKey,
        val engine: KeyEngine<TestKey, String>,
        val sourceOfTruth: MatrixSourceOfTruth,
        val overlay: MatrixOverlay,
        val mappingGate: SequencedGate,
        val readerDeliveryGate: SequencedGate,
        val beforeProjectionApplyGate: SequencedGate,
        val beforeProjectionDeliveryGate: SequencedGate,
        val afterProjectionDeliveryGate: SequencedGate,
        val projectionReadinessGate: SequencedGate,
        val projectionAuthorizationGate: SequencedGate,
    ) {
        fun releaseAll() {
            sourceOfTruth.releaseAll()
            mappingGate.releaseAll()
            readerDeliveryGate.releaseAll()
            beforeProjectionApplyGate.releaseAll()
            beforeProjectionDeliveryGate.releaseAll()
            afterProjectionDeliveryGate.releaseAll()
            projectionReadinessGate.releaseAll()
            projectionAuthorizationGate.releaseAll()
        }
    }

    private class SequencedGate {
        private val queued = ArrayDeque<Step>()
        private val allSteps = mutableListOf<Step>()

        fun gateNext(): Step =
            Step().also { step ->
                queued.addLast(step)
                allSteps += step
            }

        suspend fun awaitIfQueued() {
            val step = queued.removeFirstOrNull() ?: return
            step.entered.complete(Unit)
            try {
                step.awaitRelease()
            } finally {
                step.exited.complete(Unit)
            }
        }

        fun releaseAll() {
            allSteps.forEach { it.requestRelease() }
        }

        class Step {
            val entered = CompletableDeferred<Unit>()
            private val release = CompletableDeferred<Unit>()
            val exited = CompletableDeferred<Unit>()

            fun requestRelease() {
                release.complete(Unit)
            }

            suspend fun releaseAndAwait() {
                requestRelease()
                exited.await()
            }

            suspend fun awaitRelease() {
                release.await()
            }
        }
    }

    private class MatrixSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val liveRows = MutableSharedFlow<String?>()
        private val queuedWrites = ArrayDeque<WriteStep>()
        private val allWrites = mutableListOf<WriteStep>()
        private var readerCalls = 0
        private var current: String? = "seed"
        val liveReaderStarted = CompletableDeferred<Unit>()

        fun gateNextWrite(): WriteStep =
            WriteStep().also { step ->
                queuedWrites.addLast(step)
                allWrites += step
            }

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            val isLiveReader = readerCalls >= 2
            return flow {
                if (isLiveReader) liveReaderStarted.complete(Unit)
                emit(current)
                if (isLiveReader) {
                    liveRows.collect { value -> emit(value) }
                }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            val step =
                queuedWrites.removeFirstOrNull()
                    ?: error("Every matrix write must install its deterministic return gate.")
            current = value
            liveRows.emit(value)
            step.published.complete(Unit)
            step.releaseReturn.await()
        }

        override suspend fun delete(key: TestKey) {
            current = null
            liveRows.emit(null)
        }

        fun releaseAll() {
            allWrites.forEach { it.releaseReturn.complete(Unit) }
        }

        class WriteStep {
            val published = CompletableDeferred<Unit>()
            val releaseReturn = CompletableDeferred<Unit>()
        }
    }

    private class MatrixOverlay : Overlay<TestKey, String> {
        private val signals = MutableSharedFlow<StoreKey>(replay = 1)
        private val calls = Channel<ApplyCall>(Channel.UNLIMITED)
        private var retired = false

        override fun apply(
            key: TestKey,
            base: String?,
        ): String? {
            check(calls.trySend(ApplyCall(base = base, retired = retired)).isSuccess)
            return if (retired) base else "optimistic"
        }

        override val changes: Flow<StoreKey> = signals

        suspend fun retire(key: TestKey) {
            check(!retired)
            retired = true
            signals.emit(key)
        }

        suspend fun awaitCall(): ApplyCall = calls.receive()

        fun clearCalls() {
            while (calls.tryReceive().isSuccess) Unit
        }

        data class ApplyCall(
            val base: String?,
            val retired: Boolean,
        )
    }
}
