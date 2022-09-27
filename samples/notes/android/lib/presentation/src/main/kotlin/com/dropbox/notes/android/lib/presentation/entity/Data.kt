package com.dropbox.notes.android.lib.presentation.entity

interface Data {
    interface Default : Data
    interface Success : Data
    interface Failure : Data
}