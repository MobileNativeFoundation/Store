package org.mobilenativefoundation.store.store5.util.model

enum class SettingVariable(val value: String) {
    OfflineFilesStatus("\${OFFLINE_FILES_STATUS}"),
    OfflineFileCount("\${OFFLINE_FILE_COUNT}");
    companion object {
        private val values = values().associateBy { it.value }
        fun lookup(value: String): SettingVariable? = values[value]
    }
}
