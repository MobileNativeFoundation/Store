package com.dropbox.external.store5.sample.notes.android.app.wiring

import com.dropbox.external.store5.sample.notes.android.common.scoping.AppScope
import com.dropbox.external.store5.sample.notes.android.common.scoping.SingleIn
import com.squareup.anvil.annotations.MergeComponent

@SingleIn(AppScope::class)
@MergeComponent(AppScope::class)
interface AppComponent