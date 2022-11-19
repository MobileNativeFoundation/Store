package com.dropbox.notes.android.feature.account

import androidx.compose.foundation.layout.Column
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import com.dropbox.notes.android.common.scoping.inject

@Composable
fun AccountTab(dependencies: AccountTabUserDependencies = inject()) {

    Column {
        Text(text = dependencies.user.name)
        Text(text = dependencies.user.email)
    }
}



