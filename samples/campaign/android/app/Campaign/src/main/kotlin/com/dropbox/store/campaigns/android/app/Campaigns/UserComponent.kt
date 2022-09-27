package com.dropbox.store.campaigns.android.app

import com.dropbox.store.campaigns.android.common.scope.SingleIn
import com.dropbox.store.campaigns.android.common.scope.UserScope
import com.squareup.anvil.annotations.MergeSubcomponent

@SingleIn(UserScope::class)
@MergeSubcomponent(UserScope::class)
interface UserComponent