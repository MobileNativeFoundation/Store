package com.dropbox.android.external.store4

/**
 * Marks declarations that are still **experimental** in store API.
 * Declarations marked with this annotation are unstable and subject to change.
 */
@MustBeDocumented
@Retention(value = AnnotationRetention.BINARY)
@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
annotation class ExperimentalStoreApi
