package com.dropbox.notes.android.app.wiring

import com.dropbox.notes.android.common.entity.User
import com.dropbox.notes.android.common.scoping.AppScope
import com.dropbox.notes.android.common.scoping.SingleIn
import com.dropbox.notes.android.common.scoping.UserScope
import com.squareup.anvil.annotations.ContributesSubcomponent
import com.squareup.anvil.annotations.ContributesTo
import dagger.BindsInstance

@SingleIn(UserScope::class)
@ContributesSubcomponent(scope = UserScope::class, parentScope = AppScope::class)
interface UserComponent {
    @ContributesSubcomponent.Factory
    interface Factory {
        fun create(
            @BindsInstance user: User
        ): UserComponent
    }

    @ContributesTo(AppScope::class)
    interface ParentBindings {
        fun userComponentFactory(): Factory
    }
}