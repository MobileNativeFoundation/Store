package com.dropbox.notes.android.lib.fig.color

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.graphics.Color

@Stable
class StatefulColor(
    text: Color,
    border: Color,
    background: Color,
) {
    var text: Color by mutableStateOf(text, structuralEqualityPolicy())
        private set
    var border: Color by mutableStateOf(border, structuralEqualityPolicy())
        private set
    var background: Color by mutableStateOf(background, structuralEqualityPolicy())
        private set

    fun copy(
        text: Color = this.text,
        border: Color = this.border,
        background: Color = this.background,
    ): StatefulColor = StatefulColor(
        text,
        border,
        background,
    )

    override fun toString(): String = "StatefulColor(" +
        "text=$text, " +
        "border=$border, " +
        "background=$background" +
        ")"
}