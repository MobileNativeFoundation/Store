package com.dropbox.market.notes.android.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dropbox.market.notes.android.R
import com.dropbox.market.notes.android.fig.Fig
import com.dropbox.market.notes.android.ui.viewmodel.NotesState

@Composable
fun StatusBanner(state: NotesState, isOffline: Boolean = false) {
    when (state) {
        NotesState.Initial -> SyncingStatusBanner()
        NotesState.Loading -> SyncingStatusBanner()
        is NotesState.Success -> {
            if (isOffline) {
                OfflineStatusBanner()
            } else {
                OnlineStatusBanner()
            }
        }
    }
}

@Composable
private fun OfflineStatusBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Fig.Colors.gray.background)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {

        Icon(
            painter = painterResource(id = R.drawable.ic_cloud_offline),
            contentDescription = null,
            tint = Fig.Colors.gray.border,
            modifier = Modifier.size(40.dp)
        )
    }
}

@Composable
private fun SyncingStatusBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Fig.Colors.blue.background)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {

        Icon(
            painter = painterResource(id = R.drawable.ic_syncing),
            contentDescription = null,
            tint = Fig.Colors.blue.border,
            modifier = Modifier.size(40.dp)
        )
    }
}

@Composable
private fun OnlineStatusBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Fig.Colors.green.background)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {

        Icon(
            painter = painterResource(id = R.drawable.ic_cloud_done),
            contentDescription = null,
            tint = Fig.Colors.green.border,
            modifier = Modifier.size(40.dp)
        )
    }
}