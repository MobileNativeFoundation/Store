package com.dropbox.notes.android.lib.fig.color

import androidx.compose.material.Colors as MaterialColors

internal fun Colors.asMaterialColors(): MaterialColors = MaterialColors(
    primary = accent,
    primaryVariant = accent,
    secondary = accent,
    secondaryVariant = accent,
    background = standard.background,
    surface = standard.background,
    error = alert.text,
    onPrimary = if (isLight) secondary else standard.text,
    onSecondary = if (isLight) standard.text else secondary,
    onBackground = standard.text,
    onSurface = standard.text,
    onError = if (isLight) secondary else standard.text,
    isLight = isLight
)