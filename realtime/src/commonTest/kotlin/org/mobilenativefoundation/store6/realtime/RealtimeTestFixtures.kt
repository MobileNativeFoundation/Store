@file:OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)

package org.mobilenativefoundation.store6.realtime

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.mutations.MutationAck
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationKeyIdentity
import org.mobilenativefoundation.store6.mutations.MutationKeyResolver
import org.mobilenativefoundation.store6.mutations.MutationPresentAck
import org.mobilenativefoundation.store6.mutations.MutationPush
import org.mobilenativefoundation.store6.mutations.MutationRetirement
import org.mobilenativefoundation.store6.mutations.MutationRetirementAck
import org.mobilenativefoundation.store6.mutations.MutationServer
import org.mobilenativefoundation.store6.mutations.MutationStore
import org.mobilenativefoundation.store6.mutations.mutationStore
import org.mobilenativefoundation.store6.mutations.mutatorRegistry

internal class RealtimeTestKey(
    private val id: String,
    override val namespace: StoreNamespace = REALTIME_NAMESPACE,
) : StoreKey {
    override fun canonicalId(): String = id
}

internal val REALTIME_NAMESPACE = StoreNamespace("realtime")

internal class RecordingWriteHandle : StoreWriteHandle<RealtimeTestKey, String> {
    val events = mutableListOf<String>()

    override suspend fun apply(
        key: RealtimeTestKey,
        value: String,
    ) {
        events += "apply"
    }

    override suspend fun markStale(key: RealtimeTestKey) {
        events += "markStale"
    }

    override suspend fun confirmFresh(
        key: RealtimeTestKey,
        etag: String?,
    ) {
        events += "confirmFresh"
    }
}

internal class RecordingFetcher : Fetcher<RealtimeTestKey, String> {
    val etags = mutableListOf<String?>()
    var fetches: Int = 0
        private set

    override suspend fun fetch(
        key: RealtimeTestKey,
        etag: String?,
    ): FetcherResult<String> {
        fetches += 1
        etags += etag
        return FetcherResult.Success("fetched-$fetches", etag = "server-$fetches")
    }
}

internal object RealtimeTestKeyResolver : MutationKeyResolver<RealtimeTestKey> {
    override suspend fun resolve(identity: MutationKeyIdentity): RealtimeTestKey? =
        if (identity.namespace == REALTIME_NAMESPACE.value) {
            RealtimeTestKey(identity.canonicalId)
        } else {
            null
        }
}

internal object RealtimeStringCodec : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): String = bytes.decodeToString()
}

internal class UnusedMutationServer : MutationServer<RealtimeTestKey, String> {
    override suspend fun push(
        request: MutationPush<RealtimeTestKey, String>,
    ): MutationAck<RealtimeTestKey, String> =
        MutationPresentAck(
            authoritative = "unused",
            etag = null,
            canonicalKey = null,
        )

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(confirmedThroughSequence = request.retiredThroughSequence)
}

internal fun mutationStoreForRealtime(
    fetch: suspend (RealtimeTestKey) -> String,
): MutationStore<RealtimeTestKey, String> =
    mutationStore(
        registry = mutatorRegistry<RealtimeTestKey, String> { },
        server = UnusedMutationServer(),
        keyResolver = RealtimeTestKeyResolver,
        valueCodecVersion = 1,
        valueCodec = RealtimeStringCodec,
    ) {
        fetcher { fetch(it) }
    }
