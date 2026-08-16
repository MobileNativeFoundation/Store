// docs:snippet:mutations-journal-sqldelight-install
@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

import org.mobilenativefoundation.store6.mutations.mutationStore
import org.mobilenativefoundation.store6.mutations.sqldelight.SqlDelightMutationJournalStorage

val journal =
    SqlDelightMutationJournalStorage(
        driver = driver,
        transacter = database,
    )

val store =
    mutationStore(
        registry = registry,
        server = server,
        keyResolver = keyResolver,
        valueCodecVersion = 1,
        valueCodec = valueCodec,
    ) {
        fetcher(networkFetcher)
        journalStorage(journal)
    }
// docs:snippet:end

class JournalStorageDocsKey(
    private val id: String,
) : org.mobilenativefoundation.store6.core.StoreKey {
    override val namespace: org.mobilenativefoundation.store6.core.StoreNamespace =
        org.mobilenativefoundation.store6.core.StoreNamespace("docs-journal")

    override fun canonicalId(): String = id
}

data class JournalStorageDocsValue(
    val value: String,
)

private val driver: app.cash.sqldelight.db.SqlDriver
    get() = error("Compile-only documentation fixture")

private val database: app.cash.sqldelight.Transacter
    get() = error("Compile-only documentation fixture")

private val registry:
    org.mobilenativefoundation.store6.mutations.MutatorRegistry<
        JournalStorageDocsKey,
        JournalStorageDocsValue,
    >
    get() = error("Compile-only documentation fixture")

private val server:
    org.mobilenativefoundation.store6.mutations.MutationServer<
        JournalStorageDocsKey,
        JournalStorageDocsValue,
    >
    get() = error("Compile-only documentation fixture")

private val keyResolver:
    org.mobilenativefoundation.store6.mutations.MutationKeyResolver<JournalStorageDocsKey>
    get() = error("Compile-only documentation fixture")

private val valueCodec:
    org.mobilenativefoundation.store6.mutations.MutationCodec<JournalStorageDocsValue>
    get() = error("Compile-only documentation fixture")

private val networkFetcher:
    org.mobilenativefoundation.store6.core.seam.Fetcher<JournalStorageDocsKey, JournalStorageDocsValue>
    get() = error("Compile-only documentation fixture")
