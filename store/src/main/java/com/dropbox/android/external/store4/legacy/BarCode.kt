package com.dropbox.android.external.store4.legacy

/**
 * Barcode is used as an example unique
 * identifier for a particular [com.dropbox.android.external.store4.Store]
 *
 *
 * Barcode will be passed to Fetcher
 * and [com.dropbox.android.external.store4.impl.SourceOfTruth]
 */
@Deprecated("here for testing")
data class BarCode(val type: String, val key: String)
