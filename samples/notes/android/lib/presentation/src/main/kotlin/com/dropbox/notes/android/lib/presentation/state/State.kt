package com.dropbox.notes.android.lib.presentation.state

import com.dropbox.notes.android.lib.presentation.entity.Data

sealed class State<out D : Data> {
    object Initial : State<Nothing>()
    object Loading : State<Nothing>()
    data class Success<out D : Data.Success>(val value: D) : State<D>()
    data class Failure<out D : Data.Failure>(val error: Throwable, val value: D) : State<D>()
}