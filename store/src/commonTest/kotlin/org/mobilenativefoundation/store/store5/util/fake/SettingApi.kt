package org.mobilenativefoundation.store.store5.util.fake

import org.mobilenativefoundation.store.store5.util.model.Setting

internal class SettingApi {
    internal val db = mutableMapOf<String, Setting.Unprocessed>()
    internal var counter = 0

    init {
        seed()
    }

    fun get(key: String, fail: Boolean, ttl: Long?): Setting.Unprocessed {
        counter += 1
        if (fail) {
            throw Exception()
        }

        val setting = db[key]!!

        return if (ttl != null) {
            setting.copy(ttl = ttl)
        } else {
            setting
        }
    }

    private fun seed() {
        db["1"] = Settings.Tag.OfflineFiles.Unprocessed
        counter = 0
    }
}

object Settings {
    object Tag {
        const val ID = "1"

        object OfflineFiles {

            const val OFFLINE_FILES_STATUS = "ON"
            const val OFFLINE_FILE_COUNT = 72

            val Unprocessed = Setting.Unprocessed(
                id = ID,
                title = "Offline Files: \${OFFLINE_FILES_STATUS} \${OFFLINE_FILE_COUNT}",
                subtitle = "Offline Files: \${OFFLINE_FILES_STATUS} \${OFFLINE_FILE_COUNT}",
                label = "\${OFFLINE_FILE_COUNT}",
                status = "\${OFFLINE_FILES_STATUS}"
            )

            val Processed = Setting.Processed(
                id = ID,
                title = "Offline Files: $OFFLINE_FILES_STATUS $OFFLINE_FILE_COUNT",
                subtitle = "Offline Files: $OFFLINE_FILES_STATUS $OFFLINE_FILE_COUNT",
                label = "$OFFLINE_FILE_COUNT",
                status = OFFLINE_FILES_STATUS
            )
        }
    }
}
