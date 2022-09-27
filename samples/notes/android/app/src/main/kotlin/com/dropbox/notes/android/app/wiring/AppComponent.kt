package com.dropbox.notes.android.app.wiring

import com.dropbox.notes.android.common.scoping.AppScope
import com.dropbox.notes.android.common.scoping.SingleIn
import com.squareup.anvil.annotations.MergeComponent

@SingleIn(AppScope::class)
@MergeComponent(AppScope::class)
interface AppComponent