package com.dropbox.store.campaigns.android.common.data

import com.dropbox.store.campaigns.android.common.entity.Notification

object LocalNotifications {
    private val One = Notification("1", "Follow")
    private val Two = Notification("2", "Follow")
    private val Three = Notification("3", "Follow")
    private val Four = Notification("4", "Follow")
    private val Five = Notification("5", "Follow")

    fun list(): List<Notification> = listOf(One, Two, Three, Four, Five)
    fun list(n: Int): List<Notification> = list().subList(0, n)
}