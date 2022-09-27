package com.dropbox.notes.android.app.wiring

import com.dropbox.notes.android.common.scoping.NoteScope
import com.dropbox.notes.android.common.scoping.SingleIn
import com.dropbox.notes.android.common.scoping.UserScope
import com.squareup.anvil.annotations.ContributesSubcomponent
import com.squareup.anvil.annotations.ContributesTo

@SingleIn(NoteScope::class)
@ContributesSubcomponent(scope = NoteScope::class, parentScope = UserScope::class)
interface NoteComponent {
    @ContributesSubcomponent.Factory
    interface Factory {
        fun create(): NoteComponent
    }

    @ContributesTo(UserScope::class)
    interface ParentBindings {
        fun noteComponentFactory(): Factory
    }
}