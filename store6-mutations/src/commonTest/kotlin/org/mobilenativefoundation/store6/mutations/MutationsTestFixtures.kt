@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import app.cash.turbine.ReceiveTurbine
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult

internal class MutationsTestKey(
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("mutations")

    override fun canonicalId(): String = id
}

/**
 * Test-only dual-role backend for the mutations tracer.
 *
 * [MutationServer] intentionally has only the push half. This fixture additionally exposes [load]
 * for a Store fetcher, so [offline] can take the test's server reads and writes offline together.
 * [pushBehavior] remains scriptable for acknowledgements and failures.
 */
internal class FakeBackend(
    private val fallbackValue: String = "base",
) : MutationServer<MutationsTestKey, String> {
    private val confirmed = mutableMapOf<KeyIdentity, String>()

    internal var offline: Boolean = false
    internal var fetchCount: Int = 0
        private set
    internal val pushedValues: MutableList<String> = mutableListOf()
    internal var pushBehavior:
        suspend (MutationsTestKey, String) -> MutationAck<String> = { _, value ->
            MutationAck(echo = value, etag = "etag-${pushedValues.size}")
        }

    internal fun seed(
        key: MutationsTestKey,
        value: String,
    ) {
        confirmed[key.identity()] = value
    }

    internal suspend fun load(key: MutationsTestKey): String {
        fetchCount += 1
        check(!offline) { "backend is offline" }
        return confirmed[key.identity()] ?: fallbackValue
    }

    override suspend fun push(
        key: MutationsTestKey,
        value: String,
    ): MutationAck<String> {
        check(!offline) { "backend is offline" }
        pushedValues += value
        return pushBehavior(key, value).also { ack ->
            confirmed[key.identity()] = ack.echo
        }
    }
}

internal fun <K : StoreKey, V : Any> echoingMutationServer(): MutationServer<K, V> =
    MutationServer { _, value -> MutationAck(echo = value, etag = null) }

internal suspend fun ReceiveTurbine<StoreResult<String>>.awaitData(): StoreResult.Data<String> {
    while (true) {
        when (val result = awaitItem()) {
            is StoreResult.Data -> return result
            is StoreResult.Error,
            is StoreResult.Loading,
            is StoreResult.Revalidated,
            -> Unit
        }
    }
}

internal suspend fun ReceiveTurbine<StoreResult<String>>.awaitConfirmed(): StoreResult.Data<String> {
    while (true) {
        val result = awaitData()
        if (result.origin != Origin.OVERLAY) return result
    }
}
