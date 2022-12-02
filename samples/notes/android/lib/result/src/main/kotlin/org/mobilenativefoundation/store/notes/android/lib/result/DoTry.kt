package org.mobilenativefoundation.store.notes.android.lib.result

inline fun <R> doTry(onTry: () -> R, onCatch: (Throwable) -> R): R {
    return try {
        onTry()
    } catch (throwable: Throwable) {
        onCatch(throwable)
    }
}