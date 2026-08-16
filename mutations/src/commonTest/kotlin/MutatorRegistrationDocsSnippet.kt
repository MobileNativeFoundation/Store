// docs:snippet:mutations-mutators-five-shapes
@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationPresence
import org.mobilenativefoundation.store6.mutations.MutatorRef
import org.mobilenativefoundation.store6.mutations.MutatorRegistry
import org.mobilenativefoundation.store6.mutations.StaleSet
import org.mobilenativefoundation.store6.mutations.mutatorRegistry

private class UserKey(private val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")
    override fun canonicalId(): String = id
}

private data class User(val id: String, val name: String)
private data class Rename(val name: String)
private data class NewUser(val id: String, val name: String)

private sealed interface UserCommand {
    data class Replace(val user: User) : UserCommand
    data object Delete : UserCommand
    data object Decline : UserCommand
}

private data class UserMutators(
    val registry: MutatorRegistry<UserKey, User>,
    val genericRef: MutatorRef<UserKey, User, UserCommand>,
    val updateRef: MutatorRef<UserKey, User, Rename>,
    val createRef: MutatorRef<UserKey, User, NewUser>,
    val deleteRef: MutatorRef<UserKey, User, Unit>,
    val upsertRef: MutatorRef<UserKey, User, User>,
)

private fun noStales(): StaleSet<UserKey> =
    StaleSet(keys = emptySet(), namespaces = emptySet())

private fun buildUserMutators(
    userCommandCodec: MutationCodec<UserCommand>,
    renameCodec: MutationCodec<Rename>,
    newUserCodec: MutationCodec<NewUser>,
    userCodec: MutationCodec<User>,
): UserMutators {
    lateinit var genericRef: MutatorRef<UserKey, User, UserCommand>
    lateinit var updateRef: MutatorRef<UserKey, User, Rename>
    lateinit var createRef: MutatorRef<UserKey, User, NewUser>
    lateinit var deleteRef: MutatorRef<UserKey, User, Unit>
    lateinit var upsertRef: MutatorRef<UserKey, User, User>

    val registry =
        mutatorRegistry<UserKey, User> {
            genericRef =
                mutator(
                    id = "user-command",
                    version = 1,
                    codec = userCommandCodec,
                    stales = { _, _ -> noStales() },
                    project = { _, command ->
                        when (command) {
                            is UserCommand.Replace -> MutationPresence.Present(command.user)
                            UserCommand.Delete -> MutationPresence.Absent
                            UserCommand.Decline -> null
                        }
                    },
                )

            updateRef =
                update(
                    id = "rename-user",
                    version = 1,
                    codec = renameCodec,
                    stales = { _, _ -> noStales() },
                    project = { user, rename -> user.copy(name = rename.name) },
                )

            createRef =
                create(
                    id = "create-user",
                    version = 1,
                    codec = newUserCodec,
                    stales = { _, _ -> noStales() },
                    project = { args -> User(id = args.id, name = args.name) },
                )

            deleteRef =
                delete(
                    id = "delete-user",
                    stales = { _, _ -> noStales() },
                )

            upsertRef =
                upsert(
                    id = "put-user",
                    version = 1,
                    codec = userCodec,
                    stales = { _, _ -> noStales() },
                    project = { _, user -> MutationPresence.Present(user) },
                )
        }

    return UserMutators(
        registry = registry,
        genericRef = genericRef,
        updateRef = updateRef,
        createRef = createRef,
        deleteRef = deleteRef,
        upsertRef = upsertRef,
    )
}
// docs:snippet:end
