package com.dropbox.market.notes.android.fig.typography

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dropbox.market.notes.android.fig.R

@Stable
val DefaultTitleFontFamily = FontFamily(
    Font(R.font.sharp_grotesk_book)
)

@Stable
val DefaultFontFamily = FontFamily(
    Font(R.font.atlasgrotesk_regular),
    Font(R.font.atlasgrotesk_medium, FontWeight.Medium)
)

@Immutable
data class Typography internal constructor(
    val titleLarge: TextStyle,
    val titleStandard: TextStyle,
    val titleSmall: TextStyle,
    val labelXLarge: TextStyle,
    val labelLarge: TextStyle,
    val labelStandard: TextStyle,
    val labelSmall: TextStyle,
    val labelXSmall: TextStyle,
    val paragraphXLarge: TextStyle,
    val paragraphLarge: TextStyle,
    val paragraphStandard: TextStyle,
    val paragraphSmall: TextStyle,
    val monoLarge: TextStyle,
    val monoStandard: TextStyle,
    val monoSmall: TextStyle,
    val monoXSmall: TextStyle,
) {
    constructor(
        defaultTitleFontFamily: FontFamily = DefaultTitleFontFamily,
        defaultFontFamily: FontFamily = DefaultFontFamily,
        titleLarge: TextStyle = TextStyle(
            fontSize = 28.sp,
            lineHeight = 36.sp
        ),
        titleStandard: TextStyle = TextStyle(
            fontSize = 22.sp,
            lineHeight = 28.sp
        ),
        titleSmall: TextStyle = TextStyle(
            fontSize = 16.sp,
            lineHeight = 26.sp
        ),
        labelXLarge: TextStyle = TextStyle(
            fontSize = 20.sp,
            lineHeight = 26.sp
        ),
        labelLarge: TextStyle = TextStyle(
            fontSize = 16.sp,
            lineHeight = 20.sp
        ),
        labelStandard: TextStyle = TextStyle(
            fontSize = 14.sp,
            lineHeight = 18.sp
        ),
        labelSmall: TextStyle = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp
        ),
        labelXSmall: TextStyle = TextStyle(
            fontSize = 10.sp,
            lineHeight = 14.sp
        ),
        paragraphXLarge: TextStyle = TextStyle(
            fontSize = 20.sp,
            lineHeight = 30.sp
        ),
        paragraphLarge: TextStyle = TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp
        ),
        paragraphStandard: TextStyle = TextStyle(
            fontSize = 14.sp,
            lineHeight = 22.sp
        ),
        paragraphSmall: TextStyle = TextStyle(
            fontSize = 12.sp,
            lineHeight = 20.sp
        ),
        monoLarge: TextStyle = TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontFamily = FontFamily.Monospace
        ),
        monoStandard: TextStyle = TextStyle(
            fontSize = 14.sp,
            lineHeight = 22.sp,
            fontFamily = FontFamily.Monospace
        ),
        monoSmall: TextStyle = TextStyle(
            fontSize = 12.sp,
            lineHeight = 20.sp,
            fontFamily = FontFamily.Monospace
        ),
        monoXSmall: TextStyle = TextStyle(
            fontSize = 10.sp,
            lineHeight = 18.sp,
            fontFamily = FontFamily.Monospace
        ),
    ) : this(
        titleLarge.withDefaultFontFamily(defaultTitleFontFamily),
        titleStandard.withDefaultFontFamily(defaultTitleFontFamily),
        titleSmall.withDefaultFontFamily(defaultTitleFontFamily),
        labelXLarge.withDefaultFontFamily(defaultFontFamily),
        labelLarge.withDefaultFontFamily(defaultFontFamily),
        labelStandard.withDefaultFontFamily(defaultFontFamily),
        labelSmall.withDefaultFontFamily(defaultFontFamily),
        labelXSmall.withDefaultFontFamily(defaultFontFamily),
        paragraphXLarge.withDefaultFontFamily(defaultFontFamily),
        paragraphLarge.withDefaultFontFamily(defaultFontFamily),
        paragraphStandard.withDefaultFontFamily(defaultFontFamily),
        paragraphSmall.withDefaultFontFamily(defaultFontFamily),
        monoLarge,
        monoStandard,
        monoSmall,
        monoXSmall,
    )
}

internal fun TextStyle.withDefaultFontFamily(default: FontFamily): TextStyle {
    return if (fontFamily != null) this else copy(fontFamily = default)
}


