@file:OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
@file:Suppress("unused")

package org.mobilenativefoundation.store6.devtools.docs

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
// docs:snippet:guides-devtools-composite-install
import org.mobilenativefoundation.store6.devtools.StoreDevtoolsMonitor
import org.mobilenativefoundation.store6.devtools.StoreTelemetryLogger
import org.mobilenativefoundation.store6.devtools.storeTelemetryOf

val logger = StoreTelemetryLogger()
val monitor = StoreDevtoolsMonitor()

val users = store<UserKey, User> {
    fetcher(userFetcher)
    telemetry(storeTelemetryOf(logger, monitor))
}
// docs:snippet:end

public class UserKey(
    val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")

    override fun canonicalId(): String = id
}

public data class User(
    val id: String,
    val name: String,
)

internal val userFetcher: Fetcher<UserKey, User>
    get() =
        object : Fetcher<UserKey, User> {
            override suspend fun fetch(key: UserKey, etag: String?): FetcherResult<User> =
                FetcherResult.Success(User(key.id, "User ${key.id}"), etag)
        }

private fun compileLoggerInstall() {
    val loggerOnlyUsers = store<UserKey, User> {
        fetcher(userFetcher)
        // docs:snippet:guides-devtools-logger-install
        telemetry(StoreTelemetryLogger())
        // docs:snippet:end
    }
    loggerOnlyUsers.close()
}
