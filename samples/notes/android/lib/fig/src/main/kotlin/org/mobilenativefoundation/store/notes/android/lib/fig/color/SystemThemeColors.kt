package org.mobilenativefoundation.store.notes.android.lib.fig.color

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

@Composable
fun systemThemeColors() = if (isSystemInDarkTheme()) FigColors.Dark.create() else FigColors.Light.create()