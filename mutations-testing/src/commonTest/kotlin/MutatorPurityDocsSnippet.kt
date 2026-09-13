// docs:snippet:mutations-testing-projector-purity
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.mutations.MutationPresence
import org.mobilenativefoundation.store6.mutations.StaleSet
import org.mobilenativefoundation.store6.mutations.testing.MutatorAmbientProbe
import org.mobilenativefoundation.store6.mutations.testing.MutatorPurityContractKit
import org.mobilenativefoundation.store6.mutations.testing.MutatorPuritySample
import org.mobilenativefoundation.store6.mutations.testing.mutatorPuritySubject

@OptIn(ExperimentalStoreApi::class)
class RenameProjectorContractTest :
    MutatorPurityContractKit<UserKey, User, Rename, Pair<String, String>>() {
    private var ambientLocaleTag: String = "en-US"

    override fun createSubject() =
        mutatorPuritySubject<UserKey, User, Rename, Pair<String, String>>(
            id = "rename",
            version = 1,
            codec = RenameCodec,
            stales = { _, _ -> StaleSet(keys = emptySet(), namespaces = emptySet()) },
            samples =
                listOf(
                    MutatorPuritySample(
                        name = "rename a present user",
                        newBase = { MutationPresence.Present(User(id = "42", name = "Ada")) },
                        newArgs = { Rename(name = "Grace") },
                    ),
                ),
            snapshotValue = { user -> user.id to user.name },
            ambientProbes =
                listOf(
                    MutatorAmbientProbe(
                        name = "locale tag",
                        enterBaseline = { ambientLocaleTag = "en-US" },
                        enterChanged = { ambientLocaleTag = "tr-TR" },
                        restore = { ambientLocaleTag = "en-US" },
                    ),
                ),
            project = { base, rename ->
                when (base) {
                    is MutationPresence.Present ->
                        MutationPresence.Present(base.value.copy(name = rename.name))
                    MutationPresence.Absent -> null
                }
            },
        )
}
// docs:snippet:end

class UserKey(
    private val id: String,
) : org.mobilenativefoundation.store6.core.StoreKey {
    override val namespace: org.mobilenativefoundation.store6.core.StoreNamespace =
        org.mobilenativefoundation.store6.core.StoreNamespace("users")

    override fun canonicalId(): String = id
}

data class User(
    val id: String,
    val name: String,
)

data class Rename(
    val name: String,
)

@OptIn(ExperimentalStoreApi::class)
private object RenameCodec :
    org.mobilenativefoundation.store6.mutations.MutationCodec<Rename> {
    override fun encode(value: Rename): ByteArray = value.name.encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): Rename {
        require(version == 1)
        return Rename(bytes.decodeToString())
    }
}
