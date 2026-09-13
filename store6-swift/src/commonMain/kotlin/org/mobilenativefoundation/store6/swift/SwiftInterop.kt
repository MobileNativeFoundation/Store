package org.mobilenativefoundation.store6.swift

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Concrete key type for Swift callers. Swift constructs Kotlin objects instead of conforming a
 * Swift class to the exported StoreKey protocol, which keeps key identity semantics
 * (equals/hashCode) entirely on the Kotlin side of the bridge.
 */
public class SwiftStoreKey(
    namespace: String,
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace(namespace)

    override fun canonicalId(): String = id

    override fun equals(other: Any?): Boolean =
        other is SwiftStoreKey && other.namespace.value == namespace.value && other.id == id

    override fun hashCode(): Int = 31 * namespace.value.hashCode() + id.hashCode()

    override fun toString(): String = "SwiftStoreKey(namespace=${namespace.value}, id=$id)"
}

public enum class StoreStateKind { LOADING, DATA, REVALIDATED, ERROR }

/**
 * Transport shape for stream states crossing the Objective-C bridge. Two constraints force the
 * flattening: kotlin.time.Duration exports as its raw packed Long, so age must cross as plain
 * milliseconds, and a sealed-interface Flow element loses its concrete element type in the
 * generated Swift, so the element must be a final class. The Swift facade lifts this back into
 * an exhaustive enum and never exposes it.
 */
public class StoreStateBridge internal constructor(
    public val kind: StoreStateKind,
    public val value: Any?,
    public val origin: Origin?,
    public val ageMillis: Long,
    public val isStale: Boolean,
    public val refreshing: Boolean,
    public val error: StoreError?,
    public val servedStale: Boolean,
)

/** Stream states for one key as a Flow with a concrete element type. */
public fun storeStates(
    store: Store<StoreKey, Any>,
    key: StoreKey,
    freshness: Freshness,
): Flow<StoreStateBridge> =
    store.stream(key, freshness).map { result ->
        when (result) {
            is StoreResult.Data -> StoreStateBridge(
                kind = StoreStateKind.DATA,
                value = result.value,
                origin = result.origin,
                ageMillis = result.age.inWholeMilliseconds,
                isStale = result.isStale,
                refreshing = result.refreshing,
                error = null,
                servedStale = false,
            )
            is StoreResult.Loading -> StoreStateBridge(
                kind = StoreStateKind.LOADING,
                value = null,
                origin = null,
                ageMillis = -1,
                isStale = false,
                refreshing = false,
                error = null,
                servedStale = false,
            )
            is StoreResult.Revalidated -> StoreStateBridge(
                kind = StoreStateKind.REVALIDATED,
                value = null,
                origin = null,
                ageMillis = result.age.inWholeMilliseconds,
                isStale = false,
                refreshing = false,
                error = null,
                servedStale = false,
            )
            is StoreResult.Error -> StoreStateBridge(
                kind = StoreStateKind.ERROR,
                value = null,
                origin = null,
                ageMillis = -1,
                isStale = false,
                refreshing = false,
                error = result.error,
                servedStale = result.servedStale,
            )
        }
    }

/**
 * Build a Store whose fetcher is provided from Swift as a completion-callback closure.
 * The completion must be invoked exactly once per fetch; a non-null value resolves the fetch and
 * a null value fails it with the given message. Cancelling the Kotlin fetch does not cancel work
 * the Swift closure has already started.
 */
public fun swiftStore(
    fetch: (key: StoreKey, completion: (value: Any?, errorMessage: String?) -> Unit) -> Unit,
): Store<StoreKey, Any> = store<StoreKey, Any> {
    fetcher { key ->
        suspendCancellableCoroutine { continuation ->
            fetch(key) { value, errorMessage ->
                if (!continuation.isActive) return@fetch
                if (value != null) {
                    continuation.resume(value)
                } else {
                    continuation.resumeWithException(
                        IllegalStateException(errorMessage ?: "Swift fetcher failed without a message"),
                    )
                }
            }
        }
    }
}

/** Freshness.MaxAge from plain milliseconds; Duration cannot be constructed across the bridge. */
public fun maxAgeFreshness(notOlderThanMillis: Long): Freshness =
    Freshness.MaxAge(notOlderThanMillis.milliseconds)

/**
 * Typed read with a declared error conversion. Store.get declares no @Throws, so a StoreException
 * crossing the Objective-C bridge from it terminates the process; this wrapper's @Throws makes the
 * failure a catchable NSError on the Swift side instead.
 */
@Throws(StoreException::class, CancellationException::class)
public suspend fun storeGet(
    store: Store<StoreKey, Any>,
    key: StoreKey,
    freshness: Freshness,
): Any = store.get(key, freshness)
