package com.dropbox.store.campaigns.android.common.repository

import com.dropbox.store.campaigns.android.common.entity.User

interface UserRepository {
    suspend fun getUser(): User
}