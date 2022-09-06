package com.dropbox.market.notes.android.fig

import com.dropbox.market.notes.android.fig.color.Colors
import com.dropbox.market.notes.android.fig.color.LocalColors
import com.dropbox.market.notes.android.fig.color.asMaterialColors
import com.dropbox.market.notes.android.fig.color.updateColorsFrom
import com.dropbox.market.notes.android.fig.shape.LocalShapes
import com.dropbox.market.notes.android.fig.typography.LocalTypography
import com.dropbox.market.notes.android.fig.typography.Typography
import com.dropbox.market.notes.android.fig.typography.asMaterialTypography
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

@Composable
fun FigTheme(
    typography: Typography = Fig.Typography,
    colors: Colors = Fig.Colors,
    shapes: Shapes = Fig.Shapes,
    content: @Composable () -> Unit
) {
    val rememberedColors = remember { colors.copy() }.apply { updateColorsFrom(colors) }

    CompositionLocalProvider(
        LocalColors provides rememberedColors,
        LocalTypography provides typography,
        LocalShapes provides shapes
    ) {
        MaterialTheme(
            colors = rememberedColors.asMaterialColors(),
            typography = typography.asMaterialTypography(),
            shapes = shapes,
            content = content
        )
    }
}