package org.mobilenativefoundation.store.notes.android.feature.account

import org.mobilenativefoundation.store.notes.android.common.api.User
import org.mobilenativefoundation.store.notes.android.common.scoping.UserScope
import com.squareup.anvil.annotations.ContributesTo

@ContributesTo(UserScope::class)
interface AccountTabUserDependencies {
    val user: User
}
