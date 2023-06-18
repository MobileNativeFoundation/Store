package org.mobilenativefoundation.store.store5.impl

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger
import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.UpdaterResult
import org.mobilenativefoundation.store.store5.impl.concurrent.ThreadSafetyController
import org.mobilenativefoundation.store.store5.impl.extensions.now
import org.mobilenativefoundation.store.store5.impl.result.EagerConflictResolutionResult

/**
 * Responsible for enabling conflict resolution.
 * Logs conflict resolution outcomes.
 * @param Key the type of key used in [RealStore].
 * @param Output the type of output used in [RealStore].
 * @param Response the type of response from network writes.
 * @property delegate the [RealStore] delegate.
 * @property updater an [Updater] to post the local changes to the network.
 * @property writeRequestStackHandler a [WriteRequestStackHandler] to manage write requests.
 * @property threadSafetyController a [ThreadSafetyController] to ensure thread safety during conflict resolution.
 * @property bookkeeper an optional [Bookkeeper] for keeping track of syncing failures.
 */
internal class RealConflictResolver<Key : Any, Output : Any, Response : Any>(
    private val delegate: RealStore<Key, *, Output, *>,
    private val updater: Updater<Key, Output, Response>,
    private val writeRequestStackHandler: WriteRequestStackHandler<Key, Output>,
    private val threadSafetyController: ThreadSafetyController<Key>,
    private val bookkeeper: Bookkeeper<Key>?
) : ConflictResolver<Key, Response> {

    private val logger = Logger.apply {
        setLogWriters(listOf(CommonWriter()))
        setTag(STORE)
    }

    override suspend fun eagerlyResolveConflicts(key: Key): EagerConflictResolutionResult<Response> {
        val eagerConflictResolutionResult = updateNetworkIfConflictsMightExist(key)
        log(eagerConflictResolutionResult)
        return eagerConflictResolutionResult
    }

    private suspend fun updateNetworkIfConflictsMightExist(key: Key):
        EagerConflictResolutionResult<Response> {
        val latest = delegate.latestOrNull(key)
        val conflictsMightExist = conflictsMightExist(key)

        return if (latest != null && conflictsMightExist) {
            val updaterResult = tryUpdateNetwork(key, latest)
            processUpdaterResult(updaterResult)
        } else {
            EagerConflictResolutionResult.Success.NoConflicts
        }
    }

    private suspend fun conflictsMightExist(key: Key): Boolean {
        return unresolvedFailedSyncExists(key) || writeRequestOnStack(key)
    }

    private suspend fun unresolvedFailedSyncExists(key: Key): Boolean {
        return bookkeeper?.getLastFailedSync(key) != null
    }

    private fun writeRequestOnStack(key: Key): Boolean {
        return writeRequestStackHandler.isEmpty(key).not()
    }

    private suspend fun tryUpdateNetwork(key: Key, latest: Output): UpdaterResult = try {
        updater.post(key, latest).let { updaterResult ->
            if (updaterResult is UpdaterResult.Success) {
                writeRequestStackHandler.update<Response>(
                    key = key,
                    created = now(),
                    updaterResult = updaterResult
                )
            }
            updaterResult
        }
    } catch (error: Throwable) {
        UpdaterResult.Error.Exception(error)
    }

    private fun processUpdaterResult(updaterResult: UpdaterResult): EagerConflictResolutionResult<Response> {
        return when (updaterResult) {
            is UpdaterResult.Error.Exception -> EagerConflictResolutionResult.Error.Exception(
                updaterResult.error
            )

            is UpdaterResult.Error.Message -> EagerConflictResolutionResult.Error.Message(
                updaterResult.message
            )

            is UpdaterResult.Success -> EagerConflictResolutionResult.Success.ConflictsResolved(
                updaterResult
            )
        }
    }

    private fun log(result: EagerConflictResolutionResult<Response>) =
        when (result) {
            is EagerConflictResolutionResult.Error.Exception -> {
                logger.e(result.error.toString())
            }

            is EagerConflictResolutionResult.Error.Message -> {
                logger.e(result.message)
            }

            is EagerConflictResolutionResult.Success.ConflictsResolved -> {
                logger.d(result.value.toString())
            }

            EagerConflictResolutionResult.Success.NoConflicts -> {
                logger.d(result.toString())
            }
        }

    companion object {
        private const val STORE = "Store"
    }
}
