package org.mobilenativefoundation.store.notes.android.lib.fig.typography

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal fun Typography.asMaterialTypography(): androidx.compose.material.Typography =
    androidx.compose.material.Typography(
        defaultFontFamily = FontFamily.Default,
        h1 = titleLarge,
        h2 = titleLarge,
        h3 = titleLarge,
        h4 = titleLarge,
        h5 = titleStandard,
        h6 = titleStandard.copy(fontWeight = FontWeight.Medium),
        subtitle1 = paragraphLarge,
        subtitle2 = paragraphStandard,
        body1 = paragraphLarge,
        body2 = paragraphStandard,
        button = labelLarge.copy(fontWeight = FontWeight.Medium, lineHeight = 24.sp),
        caption = labelSmall,
        overline = labelXSmall,
    )
