package com.dropbox.notes.android.feature.account

import com.dropbox.notes.android.common.entity.User
import com.dropbox.notes.android.common.scoping.UserScope
import com.squareup.anvil.annotations.ContributesTo

@ContributesTo(UserScope::class)
interface AccountTabUserDependencies {
    val user: User
}
