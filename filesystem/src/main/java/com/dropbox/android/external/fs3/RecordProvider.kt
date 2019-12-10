package com.dropbox.android.external.fs3

interface RecordProvider<Key> {
    fun getRecordState(key: Key): RecordState
}
