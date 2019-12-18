package com.dropbox.android.external.fs3

import com.dropbox.android.external.store4.legacy.BarCode

object BarCodeReadAllPathResolver : PathResolver<BarCode> {
    override fun resolve(key: BarCode): String = key.type + "/" + key.key
}
