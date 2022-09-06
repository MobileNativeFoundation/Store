package com.dropbox.market.notes.android.fig.color

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.dropbox.market.notes.android.fig.color.FigColors

@Composable
fun systemThemeColors() = if (isSystemInDarkTheme()) FigColors.Dark.create() else FigColors.Light.create()