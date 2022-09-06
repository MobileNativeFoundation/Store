package com.dropbox.market.notes.android.fig

import com.dropbox.market.notes.android.fig.color.Colors
import com.dropbox.market.notes.android.fig.color.LocalColors
import com.dropbox.market.notes.android.fig.shape.LocalShapes
import com.dropbox.market.notes.android.fig.typography.LocalTypography
import com.dropbox.market.notes.android.fig.typography.Typography
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