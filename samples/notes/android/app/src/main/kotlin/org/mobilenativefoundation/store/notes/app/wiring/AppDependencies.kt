package org.mobilenativefoundation.store.notes.app.wiring

import org.mobilenativefoundation.store.notes.android.common.api.Api
import org.mobilenativefoundation.store.notes.android.common.scoping.AppScope
import com.squareup.anvil.annotations.ContributesTo

@ContributesTo(AppScope::class)
interface AppDependencies {
    val api: Api
}