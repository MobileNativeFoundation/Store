package com.dropbox.notes.android.app.wiring

import com.dropbox.notes.android.common.entity.Api
import com.dropbox.notes.android.common.scoping.AppScope
import com.squareup.anvil.annotations.ContributesTo

@ContributesTo(AppScope::class)
interface AppDependencies {
    val api: Api
}