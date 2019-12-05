package com.dropbox.android.external.fs3

import com.dropbox.android.external.store4.legacy.BarCode

class BarCodeReadAllPathResolver : PathResolver<BarCode> {

    override fun resolve(barCode: BarCode): String =
            barCode.type + "/" + barCode.key
}
