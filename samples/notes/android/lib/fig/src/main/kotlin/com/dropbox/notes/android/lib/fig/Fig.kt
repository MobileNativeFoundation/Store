package com.dropbox.notes.android.lib.fig

import com.dropbox.notes.android.lib.fig.color.Colors
import com.dropbox.notes.android.lib.fig.color.LocalColors
import com.dropbox.notes.android.lib.fig.shape.LocalShapes
import com.dropbox.notes.android.lib.fig.typography.LocalTypography
import com.dropbox.notes.android.lib.fig.typography.Typography
import androidx.compose.material.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

object Fig {
    val Typography: Typography
        @Composable
        @ReadOnlyComposable
        get() = LocalTypography.current

    val Colors: Colors
        @Composable
        @ReadOnlyComposable
        get() = LocalColors.current

    val Shapes: Shapes
        @Composable
        @ReadOnlyComposable
        get() = LocalShapes.current
}