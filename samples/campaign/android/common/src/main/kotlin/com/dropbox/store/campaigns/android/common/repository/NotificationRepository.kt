package com.dropbox.store.campaigns.android.common.repository

import com.dropbox.store.campaigns.android.common.entity.Notification
import kotlinx.coroutines.flow.Flow

interface NotificationRepository {
    fun getNotifications(): Flow<Notification>
}