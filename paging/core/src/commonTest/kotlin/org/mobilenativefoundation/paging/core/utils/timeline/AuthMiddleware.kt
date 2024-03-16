package org.mobilenativefoundation.paging.core.utils.timeline

import org.mobilenativefoundation.paging.core.Middleware
import org.mobilenativefoundation.paging.core.PagingAction

class AuthMiddleware(private val authTokenProvider: () -> String) : Middleware<Id, K, P, D, E, A> {
    private fun setAuthToken(headers: MutableMap<String, String>) = headers.apply {
        this["auth"] = authTokenProvider()
    }

    override suspend fun apply(action: PagingAction<Id, K, P, D, E, A>, next: suspend (PagingAction<Id, K, P, D, E, A>) -> Unit) {
        when (action) {
            is PagingAction.User.Load -> {
                setAuthToken(action.key.params.headers)
                next(action)
            }

            is PagingAction.Load -> {
                setAuthToken(action.key.params.headers)
                next(action)
            }

            else -> next(action)
        }
    }
}