package org.mobilenativefoundation.store.store5.internal.result

import org.mobilenativefoundation.store.store5.UpdaterResult

sealed class EagerConflictResolutionResult<out Response : Any> {

    sealed class Success<Response : Any> : EagerConflictResolutionResult<Response>() {
        object NoConflicts : Success<Nothing>()
        data class ConflictsResolved<Response : Any>(val value: UpdaterResult.Success) : Success<Response>()
    }

    data class Error<E: Any>(val error: E) : EagerConflictResolutionResult<Nothing>()
}
