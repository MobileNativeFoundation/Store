package com.dropbox.android.external.store3

import com.dropbox.android.external.store4.Persister
import com.dropbox.android.external.store4.Clearable
import com.dropbox.android.external.store4.legacy.BarCode

open class ClearingPersister : Persister<Int, BarCode>, Clearable<BarCode> {
    override suspend fun read(key: BarCode): Int? {
        throw RuntimeException()
    }

    override suspend fun write(key: BarCode, raw: Int): Boolean {
        throw RuntimeException()
    }

    override fun clear(key: BarCode) {
        throw RuntimeException()
    }
}
