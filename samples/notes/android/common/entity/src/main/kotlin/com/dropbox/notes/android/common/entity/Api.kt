package com.dropbox.notes.android.common.entity

import com.dropbox.notes.android.lib.result.Result

interface Api {
    fun getUser(): Result<User, Throwable>
}