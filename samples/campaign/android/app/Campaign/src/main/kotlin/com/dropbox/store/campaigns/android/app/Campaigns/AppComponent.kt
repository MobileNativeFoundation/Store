package com.dropbox.store.campaigns.android.app

import com.dropbox.store.campaigns.android.common.scope.AppScope
import com.dropbox.store.campaigns.android.common.scope.SingleIn
import com.squareup.anvil.annotations.MergeComponent

@SingleIn(AppScope::class)
@MergeComponent(AppScope::class)
interface AppComponent {
    fun userComponent(): UserComponent
}