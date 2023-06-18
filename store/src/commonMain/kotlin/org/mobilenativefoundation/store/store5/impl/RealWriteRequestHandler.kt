package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import org.mobilenativefoundation.store.store5.StoreWriteResponse
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.UpdaterResult

/**
 * This is an internal class used by [RealMutableStore] to handle write requests.
 * @param Key The type of key used in the [RealStore].
 * @param Output The type of output in the [RealStore].
 * @property delegate The [RealStore] instance this class delegates to.
 * @property updater An [Updater] instance responsible for updating the network and local data sources.
 * @property writeRequestStackHandler A [WriteRequestStackHandler] instance responsible for handling the stacks of write requests.
 * @property bookkeeper An optional [Bookkeeper] instance responsible for recording write request failures.
 */
internal class RealWriteRequestHandler<Key : Any, Output : Any>(
    private val delegate: RealStore<Key, *, Output, *>,
    private val updater: Updater<Key, Output, *>,
    private val writeRequestStackHandler: WriteRequestStackHandler<Key, Output>,
    private val bookkeeper: Bookkeeper<Key>? = null,
) : WriteRequestHandler<Key, Output> {

    /**
     * This method is invoked when a write request is processed by [RealMutableStore].
     * First, it writes to the [RealStore] delegate.
     * Second, it attempts to update the remote data source.
     * @param request The [StoreWriteRequest] to be processed.
     * @return A [StoreWriteResponse.Success] wrapping the network response of type [Response], or otherwise a [StoreWriteResponse.Error].
     */
    override suspend fun <Response : Any> invoke(request: StoreWriteRequest<Key, Output, Response>): StoreWriteResponse {
        return try {
            delegate.write(request.key, request.value)
            handleUpdateServerResult(request)
        } catch (throwable: Throwable) {
            StoreWriteResponse.Error.Exception(throwable)
        }
    }

    private suspend fun <Response : Any> handleUpdateServerResult(request: StoreWriteRequest<Key, Output, Response>): StoreWriteResponse {
        return when (val updaterResult = tryUpdateServer(request)) {
            is UpdaterResult.Error -> handleErrorUpdaterResult(updaterResult)
            is UpdaterResult.Success -> {
                handleSuccessUpdaterResult<Response>(
                    updaterResult,
                    request.key,
                    request.created
                )
            }
        }
    }

    private suspend fun <Response : Any> tryUpdateServer(request: StoreWriteRequest<Key, Output, Response>): UpdaterResult {
        val updaterResult = postLatest<Response>(request.key)

        if (updaterResult is UpdaterResult.Success) {
            writeRequestStackHandler.update<Response>(
                key = request.key,
                created = request.created,
                updaterResult = updaterResult
            )
            bookkeeper?.clear(request.key)
        } else {
            bookkeeper?.setLastFailedSync(request.key)
        }

        return updaterResult
    }

    private suspend fun <Response : Any> postLatest(key: Key): UpdaterResult {
        val writer = writeRequestStackHandler.getLatest<Response>(key)
        val updaterResult = try {
            updater.post(key, writer.value)
        } catch (error: Throwable) {
            UpdaterResult.Error.Exception(error)
        }

        return when (updaterResult) {
            is UpdaterResult.Error.Exception -> UpdaterResult.Error.Exception(updaterResult.error)
            is UpdaterResult.Error.Message -> UpdaterResult.Error.Message(updaterResult.message)
            is UpdaterResult.Success.Untyped -> UpdaterResult.Success.Untyped(updaterResult.value)
            is UpdaterResult.Success.Typed<*> -> {
                UpdaterResult.Success.Typed(updaterResult.value)
            }
        }
    }

    private suspend fun <Response : Any> handleSuccessUpdaterResult(
        updaterResult: UpdaterResult.Success,
        key: Key,
        created: Long
    ): StoreWriteResponse {
        writeRequestStackHandler.update<Response>(key, created, updaterResult)
        return createSuccessStoreWriteResponse(updaterResult)
    }

    private fun createSuccessStoreWriteResponse(updaterResult: UpdaterResult.Success): StoreWriteResponse {
        return when (updaterResult) {
            is UpdaterResult.Success.Typed<*> -> {
                StoreWriteResponse.Success.Typed(updaterResult.value)
            }

            is UpdaterResult.Success.Untyped -> StoreWriteResponse.Success.Untyped(updaterResult.value)
        }
    }

    private fun handleErrorUpdaterResult(updaterResult: UpdaterResult.Error): StoreWriteResponse {
        return when (updaterResult) {
            is UpdaterResult.Error.Exception -> StoreWriteResponse.Error.Exception(updaterResult.error)
            is UpdaterResult.Error.Message -> StoreWriteResponse.Error.Message(updaterResult.message)
        }
    }
}
