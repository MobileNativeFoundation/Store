package org.mobilenativefoundation.store.notes.db

import org.mobilenativefoundation.store.notes.app.data.user.RealUserInfo
import org.mobilenativefoundation.store.notes.android.common.api.UserInfo

object UsersInfo {
    fun userInfo(id: String): UserInfo = when (id) {
        "1" -> RealUserInfo(Users.Tag.NAME, Users.Tag.AVATAR_URL)
        "2" -> RealUserInfo(Users.Trot.NAME, Users.Trot.AVATAR_URL)
        else -> throw NotImplementedError()
    }
}