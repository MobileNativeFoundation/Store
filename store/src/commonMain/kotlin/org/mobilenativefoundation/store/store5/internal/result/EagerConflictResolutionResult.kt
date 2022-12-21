package org.mobilenativefoundation.store.store5.internal.result

import org.mobilenativefoundation.store.store5.UpdaterResult

sealed class EagerConflictResolutionResult<out NetworkWriteResponse : Any> {

    sealed class Success<NetworkWriteResponse : Any> : EagerConflictResolutionResult<NetworkWriteResponse>() {
        object NoConflicts : Success<Nothing>()
        data class ConflictsResolved<NetworkWriteResponse : Any>(val value: UpdaterResult.Success) : Success<NetworkWriteResponse>()
    }

    sealed class Error : EagerConflictResolutionResult<Nothing>() {
        data class Message(val message: String) : Error()
        data class Exception(val error: Throwable) : Error()
    }
}
