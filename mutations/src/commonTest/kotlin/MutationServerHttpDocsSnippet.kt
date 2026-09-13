// docs:snippet:mutations-server-http-adapter
@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.StoreResults
import org.mobilenativefoundation.store6.mutations.MutationAbsentAck
import org.mobilenativefoundation.store6.mutations.MutationAck
import org.mobilenativefoundation.store6.mutations.MutationPresentAck
import org.mobilenativefoundation.store6.mutations.MutationPush
import org.mobilenativefoundation.store6.mutations.MutationRetirement
import org.mobilenativefoundation.store6.mutations.MutationRetirementAck
import org.mobilenativefoundation.store6.mutations.MutationServer

private class HttpUserKey(private val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")
    override fun canonicalId(): String = id
}

private data class HttpUser(val id: String, val name: String)

private sealed interface PushResponse {
    data class Present(
        val authoritative: HttpUser,
        val etag: String?,
        val canonicalId: String?,
    ) : PushResponse

    data class Absent(val etag: String?) : PushResponse

    data class Conflict(
        val serverMeta: StoreMeta?,
        val message: String,
        val cause: Throwable? = null,
    ) : PushResponse
}

private interface UserMutationHttpClient {
    // Deterministically encode the documented carrier fields and route only by request.identity.
    suspend fun push(request: MutationPush<HttpUserKey, HttpUser>): PushResponse

    suspend fun retire(request: MutationRetirement): Long
}

private class HttpUserMutationServer(
    private val http: UserMutationHttpClient,
) : MutationServer<HttpUserKey, HttpUser> {
    override suspend fun push(
        request: MutationPush<HttpUserKey, HttpUser>,
    ): MutationAck<HttpUserKey, HttpUser> =
        when (val response = http.push(request)) {
            is PushResponse.Present ->
                MutationPresentAck(
                    authoritative = response.authoritative,
                    etag = response.etag,
                    canonicalKey = response.canonicalId?.let(::HttpUserKey),
                )

            is PushResponse.Absent ->
                MutationAbsentAck(etag = response.etag)

            is PushResponse.Conflict ->
                throw StoreResults.exception(
                    StoreResults.conflict(response.serverMeta, response.message),
                    response.cause,
                )
        }

    override suspend fun retire(
        request: MutationRetirement,
    ): MutationRetirementAck =
        MutationRetirementAck(
            confirmedThroughSequence = http.retire(request),
        )
}
// docs:snippet:end
