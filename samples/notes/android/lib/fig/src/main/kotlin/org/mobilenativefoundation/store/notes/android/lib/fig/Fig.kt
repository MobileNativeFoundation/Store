package org.mobilenativefoundation.store.notes.android.lib.fig

import org.mobilenativefoundation.store.notes.android.lib.fig.color.Colors
import org.mobilenativefoundation.store.notes.android.lib.fig.color.LocalColors
import org.mobilenativefoundation.store.notes.android.lib.fig.shape.LocalShapes
import org.mobilenativefoundation.store.notes.android.lib.fig.typography.LocalTypography
import org.mobilenativefoundation.store.notes.android.lib.fig.typography.Typography
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