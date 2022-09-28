package com.dropbox.external.store5.sample.notes.android.app.wiring

import com.dropbox.external.store5.sample.notes.android.common.scoping.AppScope
import com.dropbox.external.store5.sample.notes.android.common.scoping.SingleIn
import com.dropbox.external.store5.sample.notes.android.common.scoping.UserScope
import com.squareup.anvil.annotations.ContributesSubcomponent
import com.squareup.anvil.annotations.ContributesTo

@SingleIn(UserScope::class)
@ContributesSubcomponent(scope = UserScope::class, parentScope = AppScope::class)
interface UserComponent {
    @ContributesSubcomponent.Factory
    interface Factory {
        fun create(): UserComponent
    }

    @ContributesTo(AppScope::class)
    interface ParentBindings {
        fun userComponentFactory(): Factory
    }
}