package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.fail

class MaintenanceCoordinatorTest {
    @Test
    fun commitsInUnrelatedNamespacesOverlap() = runTest {
        val coordinator = MaintenanceCoordinator()
        val alphaStarted = CompletableDeferred<Unit>()
        val releaseAlpha = CompletableDeferred<Unit>()
        val betaStarted = CompletableDeferred<Unit>()

        val alpha =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withCommit("alpha") {
                    alphaStarted.complete(Unit)
                    releaseAlpha.await()
                    "alpha"
                }
            }
        alphaStarted.await()

        val beta =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withCommit("beta") {
                    betaStarted.complete(Unit)
                    "beta"
                }
            }

        betaStarted.await()
        assertEquals("beta", beta.await())
        assertFalse(alpha.isCompleted)

        releaseAlpha.complete(Unit)
        assertEquals("alpha", alpha.await())
    }

    @Test
    fun namespaceMaintenanceDrainsAndBlocksOnlyItsNamespace() = runTest {
        val coordinator = MaintenanceCoordinator()
        val activeStarted = CompletableDeferred<Unit>()
        val releaseActive = CompletableDeferred<Unit>()
        val maintenanceStarted = CompletableDeferred<Unit>()
        val releaseMaintenance = CompletableDeferred<Unit>()
        val blockedCommitStarted = CompletableDeferred<Unit>()

        val active =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withCommit("alpha") {
                    activeStarted.complete(Unit)
                    releaseActive.await()
                }
            }
        activeStarted.await()

        val maintenance =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withNamespaceMaintenance("alpha") {
                    maintenanceStarted.complete(Unit)
                    releaseMaintenance.await()
                    "maintained"
                }
            }
        val blockedCommit =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withCommit("alpha") {
                    blockedCommitStarted.complete(Unit)
                    "alpha-after"
                }
            }
        val otherCommit =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withCommit("beta") { "beta" }
            }

        assertFalse(maintenanceStarted.isCompleted)
        assertFalse(blockedCommitStarted.isCompleted)
        assertEquals("beta", otherCommit.await())

        releaseActive.complete(Unit)
        active.await()
        maintenanceStarted.await()
        assertFalse(blockedCommitStarted.isCompleted)

        releaseMaintenance.complete(Unit)
        assertEquals("maintained", maintenance.await())
        assertEquals("alpha-after", blockedCommit.await())
    }

    @Test
    fun globalMaintenanceDrainsAndBlocksEveryNamespace() = runTest {
        val coordinator = MaintenanceCoordinator()
        val alphaStarted = CompletableDeferred<Unit>()
        val betaStarted = CompletableDeferred<Unit>()
        val releaseAlpha = CompletableDeferred<Unit>()
        val releaseBeta = CompletableDeferred<Unit>()
        val maintenanceStarted = CompletableDeferred<Unit>()
        val releaseMaintenance = CompletableDeferred<Unit>()
        val laterAlphaStarted = CompletableDeferred<Unit>()
        val laterBetaStarted = CompletableDeferred<Unit>()

        val alpha =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withCommit("alpha") {
                    alphaStarted.complete(Unit)
                    releaseAlpha.await()
                }
            }
        val beta =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withCommit("beta") {
                    betaStarted.complete(Unit)
                    releaseBeta.await()
                }
            }
        alphaStarted.await()
        betaStarted.await()

        val maintenance =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withGlobalMaintenance {
                    maintenanceStarted.complete(Unit)
                    releaseMaintenance.await()
                }
            }
        val laterAlpha =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withCommit("alpha") {
                    laterAlphaStarted.complete(Unit)
                }
            }
        val laterBeta =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withCommit("beta") {
                    laterBetaStarted.complete(Unit)
                }
            }

        assertFalse(laterAlphaStarted.isCompleted)
        assertFalse(laterBetaStarted.isCompleted)
        releaseAlpha.complete(Unit)
        alpha.await()
        assertFalse(maintenanceStarted.isCompleted)

        releaseBeta.complete(Unit)
        beta.await()
        maintenanceStarted.await()
        assertFalse(laterAlphaStarted.isCompleted)
        assertFalse(laterBetaStarted.isCompleted)

        releaseMaintenance.complete(Unit)
        maintenance.await()
        laterAlpha.await()
        laterBeta.await()
    }

    @Test
    fun cancelledCommitBodyReleasesItsLease() = runTest {
        val coordinator = MaintenanceCoordinator()
        val commitStarted = CompletableDeferred<Unit>()

        val commit =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withCommit("alpha") {
                    commitStarted.complete(Unit)
                    awaitCancellation()
                }
            }
        commitStarted.await()
        commit.cancelAndJoin()

        assertEquals(
            "maintained",
            coordinator.withNamespaceMaintenance("alpha") { "maintained" },
        )
    }

    @Test
    fun cancelledDrainingMaintenanceRemovesBlockedScope() = runTest {
        val coordinator = MaintenanceCoordinator()
        val activeStarted = CompletableDeferred<Unit>()
        val releaseActive = CompletableDeferred<Unit>()
        val maintenanceBodyStarted = CompletableDeferred<Unit>()
        val laterCommitStarted = CompletableDeferred<Unit>()
        val releaseLaterCommit = CompletableDeferred<Unit>()

        val active =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withCommit("alpha") {
                    activeStarted.complete(Unit)
                    releaseActive.await()
                }
            }
        activeStarted.await()

        val maintenance =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withNamespaceMaintenance("alpha") {
                    maintenanceBodyStarted.complete(Unit)
                }
            }
        assertFalse(maintenanceBodyStarted.isCompleted)
        maintenance.cancelAndJoin()

        val laterCommit =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withCommit("alpha") {
                    laterCommitStarted.complete(Unit)
                    releaseLaterCommit.await()
                }
            }
        laterCommitStarted.await()
        assertFalse(active.isCompleted)

        releaseLaterCommit.complete(Unit)
        laterCommit.await()
        releaseActive.complete(Unit)
        active.await()
    }

    @Test
    fun commitCallbackCannotEnterSameNamespaceMaintenance() = runTest {
        val coordinator = MaintenanceCoordinator()

        assertReentryFailsFast {
            coordinator.withCommit("alpha") {
                coordinator.withNamespaceMaintenance("alpha") {}
            }
        }
    }

    @Test
    fun commitCallbackCannotEnterGlobalMaintenance() = runTest {
        val coordinator = MaintenanceCoordinator()

        assertReentryFailsFast {
            coordinator.withCommit("alpha") {
                coordinator.withGlobalMaintenance {}
            }
        }
    }

    @Test
    fun maintenanceCallbackCannotEnterMaintenance() = runTest {
        val coordinator = MaintenanceCoordinator()

        assertReentryFailsFast {
            coordinator.withNamespaceMaintenance("alpha") {
                coordinator.withGlobalMaintenance {}
            }
        }
    }

    @Test
    fun maintenanceCallbackCannotEnterSameScopeCommit() = runTest {
        val coordinator = MaintenanceCoordinator()

        assertReentryFailsFast {
            coordinator.withNamespaceMaintenance("alpha") {
                coordinator.withCommit("alpha") {}
            }
        }
    }

    @Test
    fun nestedCoordinatorChainCannotReenterEarlierCoordinator() = runTest {
        val first = MaintenanceCoordinator()
        val second = MaintenanceCoordinator()

        assertReentryFailsFast {
            first.withCommit("alpha") {
                second.withCommit("beta") {
                    first.withCommit("gamma") {}
                }
            }
        }
    }

    @Test
    fun callbackMayEnterIndependentCoordinator() = runTest {
        val first = MaintenanceCoordinator()
        val second = MaintenanceCoordinator()

        assertEquals(
            "independent",
            first.withCommit("alpha") {
                second.withNamespaceMaintenance("alpha") { "independent" }
            },
        )
    }

    private suspend fun TestScope.assertReentryFailsFast(block: suspend () -> Unit) {
        val attempt =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { block() }
            }
        if (!attempt.isCompleted) {
            attempt.cancelAndJoin()
            fail("Re-entry suspended instead of failing fast")
        }

        val failure = assertIs<IllegalStateException>(attempt.await().exceptionOrNull())
        assertEquals(
            "MaintenanceCoordinator callbacks cannot re-enter the same coordinator.",
            failure.message,
        )
    }
}
