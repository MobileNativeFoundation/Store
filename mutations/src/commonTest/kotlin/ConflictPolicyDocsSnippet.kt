// docs:snippet:mutations-conflicts-policy
@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.mutations.MutationConflictResolution
import org.mobilenativefoundation.store6.mutations.MutationPresence
import org.mobilenativefoundation.store6.mutations.MutationStoreBuilder

data class Profile(
    val displayName: String,
    val avatarUrl: String,
)

fun <K : StoreKey> MutationStoreBuilder<K, Profile>.installProfileConflicts() {
    conflicts {
        precondition { candidate ->
            candidate.capturedMeta?.takeIf { it.etag != null }
        }

        merge { base, mine, theirs ->
            if (
                base is MutationPresence.Present &&
                mine is MutationPresence.Present &&
                theirs is MutationPresence.Present &&
                theirs.value.displayName == base.value.displayName
            ) {
                MutationConflictResolution.Retry(
                    MutationPresence.Present(
                        theirs.value.copy(displayName = mine.value.displayName),
                    ),
                )
            } else {
                MutationConflictResolution.ServerWins
            }
        }
    }
}
// docs:snippet:end
