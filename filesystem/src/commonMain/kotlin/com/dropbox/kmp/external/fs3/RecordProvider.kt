package com.dropbox.kmp.external.fs3

interface RecordProvider<Key> {
    fun getRecordState(key: Key): RecordState
}
