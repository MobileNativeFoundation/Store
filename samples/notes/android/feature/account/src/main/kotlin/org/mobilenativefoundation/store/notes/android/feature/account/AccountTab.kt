package org.mobilenativefoundation.store.notes.android.feature.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import coil.compose.rememberAsyncImagePainter
import org.mobilenativefoundation.store.notes.android.common.scoping.inject

@Composable
fun AccountTab(deps: AccountTabUserDependencies = inject()) {
    val painter = rememberAsyncImagePainter(deps.user.avatarUrl)

    Column {
        Text(text = deps.user.name)
        Text(text = deps.user.email)

        Image(painter = painter, contentDescription = null)
    }
}



