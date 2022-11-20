package org.mobilenativefoundation.store.notes.app.wiring

import org.mobilenativefoundation.store.notes.android.common.scoping.AppScope
import org.mobilenativefoundation.store.notes.android.common.scoping.SingleIn
import com.squareup.anvil.annotations.MergeComponent

@SingleIn(AppScope::class)
@MergeComponent(AppScope::class)
interface AppComponent