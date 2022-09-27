package com.dropbox.notes.android.lib.fig.color

import androidx.compose.ui.graphics.Color

object FigColors {
    object Light {
        private val primary: Color = Color(0xff1e1919)
        private val secondary: Color = Color(0xfff7f5f2)
        private val accent: Color = Color(0xff0061fe)
        private val buttonPrimaryHover: Color = Color(0xff0057e5)
        private val buttonPrimaryActive: Color = Color(0xff0050d0)
        private val standard: StatefulColor = StatefulColor(
            text = Color(0xff1e1919),
            border = Color(166 / 255f, 158 / 255f, 146 / 255f, 0.6f),
            background = Color.White,
        )
        private val faint: StatefulColor = StatefulColor(
            text = Color(82 / 255f, 74 / 255f, 62 / 255f, 0.82f),
            border = Color(166 / 255f, 145 / 255f, 113 / 255f, 0.2f),
            background = Color(0xfff7f5f2),
        )
        private val disabled: StatefulColor = StatefulColor(
            text = Color(82 / 255f, 74 / 255f, 62 / 255f, 0.4f),
            border = Color(0xffc0bbb4),
            background = Color(0xffc0bbb4),
        )
        private val attention: StatefulColor = StatefulColor(
            text = Color(0xff0061fe),
            border = Color(0xff0061fe),
            background = Color(0 / 255f, 97 / 255f, 254 / 255f, 0.16f),
        )
        private val success: StatefulColor = StatefulColor(
            text = Color(0xff2d7a02),
            border = Color(0xff2d7a02),
            background = Color(45 / 255f, 122 / 255f, 2 / 255f, 0.12f),
        )
        private val warning: StatefulColor = StatefulColor(
            text = Color(0xff9b6400),
            border = Color(0xff9b6400),
            background = Color(250 / 255f, 210 / 255f, 75 / 255f, 0.24f),
        )
        private val alert: StatefulColor = StatefulColor(
            text = Color(0xff9b0032),
            border = Color(0xff9b0032),
            background = Color(155 / 255f, 0 / 255f, 50 / 255f, 0.1f),
        )
        private val opacity1: Color = Color(166 / 255f, 145 / 255f, 113 / 255f, 0.14f)
        private val opacity2: Color = Color(166 / 255f, 145 / 255f, 113 / 255f, 0.24f)
        private val opacity3: Color = Color(166 / 255f, 145 / 255f, 113 / 255f, 0.32f)
        private val gray: StatefulColor = StatefulColor(
            text = Color(0xff575148),
            border = Color(0xff575148),
            background = Color(0xffFAF8F5)
        )
        private val rose: StatefulColor = StatefulColor(
            text = Color(0xffE11D47),
            border = Color(0xffE11D47),
            background = Color(0xffFEEAEC)
        )
        private val pink: StatefulColor = StatefulColor(
            text = Color(0xffDA2877),
            border = Color(0xffDA2877),
            background = Color(0xffFCEDF6)
        )
        private val fuchsia: StatefulColor = StatefulColor(
            text = Color(0xffC025D3),
            border = Color(0xffC025D3),
            background = Color(0xffFBEDFF)
        )
        private val purple: StatefulColor = StatefulColor(
            text = Color(0xff9333E9),
            border = Color(0xff9333E9),
            background = Color(0xffF6EEFF)
        )
        private val violet: StatefulColor = StatefulColor(
            text = Color(0xff7C3AED),
            border = Color(0xff7C3AED),
            background = Color(0xffF1EFFE)
        )
        private val indigo: StatefulColor = StatefulColor(
            text = Color(0xff4F45E4),
            border = Color(0xff4F45E4),
            background = Color(0xffE8EDFE)
        )
        private val blue: StatefulColor = StatefulColor(
            text = Color(0xff2463EB),
            border = Color(0xff2463EB),
            background = Color(0xffE4EFFE)
        )
        private val sky: StatefulColor = StatefulColor(
            text = Color(0xff0084C7),
            border = Color(0xff0084C7),
            background = Color(0xffE7F5FE)
        )
        private val cyan: StatefulColor = StatefulColor(
            text = Color(0xff0991B1),
            border = Color(0xff0991B1),
            background = Color(0xffD9FCF6)
        )
        private val teal: StatefulColor = StatefulColor(
            text = Color(0xff0C9488),
            border = Color(0xff0C9488),
            background = Color(0xffDBFBFE)
        )
        private val emerald: StatefulColor = StatefulColor(
            text = Color(0xff069668),
            border = Color(0xff069668),
            background = Color(0xffDDFBEB)
        )
        private val green: StatefulColor = StatefulColor(
            text = Color(0xff16A349),
            border = Color(0xff16A349),
            background = Color(0xffE4FDED)
        )
        private val lime: StatefulColor = StatefulColor(
            text = Color(0xff65A20C),
            border = Color(0xff65A20C),
            background = Color(0xffF1FCD9)
        )
        private val yellow: StatefulColor = StatefulColor(
            text = Color(0xffCA8A03),
            border = Color(0xffCA8A03),
            background = Color(0xffFEFBD2)
        )
        private val amber: StatefulColor = StatefulColor(
            text = Color(0xffD97707),
            border = Color(0xffD97707),
            background = Color(0xffFEF6D6)
        )
        private val orange: StatefulColor = StatefulColor(
            text = Color(0xffEA580B),
            border = Color(0xffEA580B),
            background = Color(0xffFFF1E0)
        )
        private val red: StatefulColor = StatefulColor(
            text = Color(0xffDC2625),
            border = Color(0xffDC2625),
            background = Color(0xffFEE9E9)
        )
        private const val IS_LIGHT = true

        fun create(
            primary: Color = Light.primary,
            secondary: Color = Light.secondary,
            accent: Color = Light.accent,
            buttonPrimaryHover: Color = Light.buttonPrimaryHover,
            buttonPrimaryActive: Color = Light.buttonPrimaryActive,
            standard: StatefulColor = Light.standard,
            faint: StatefulColor = Light.faint,
            disabled: StatefulColor = Light.disabled,
            attention: StatefulColor = Light.attention,
            success: StatefulColor = Light.success,
            warning: StatefulColor = Light.warning,
            alert: StatefulColor = Light.alert,
            opacity1: Color = Light.opacity1,
            opacity2: Color = Light.opacity2,
            opacity3: Color = Light.opacity3,
            gray: StatefulColor = Light.gray,
            rose: StatefulColor = Light.rose,
            pink: StatefulColor = Light.pink,
            fuchsia: StatefulColor = Light.fuchsia,
            purple: StatefulColor = Light.purple,
            violet: StatefulColor = Light.violet,
            indigo: StatefulColor = Light.indigo,
            blue: StatefulColor = Light.blue,
            sky: StatefulColor = Light.sky,
            cyan: StatefulColor = Light.cyan,
            teal: StatefulColor = Light.teal,
            emerald: StatefulColor = Light.emerald,
            green: StatefulColor = Light.green,
            lime: StatefulColor = Light.lime,
            yellow: StatefulColor = Light.yellow,
            amber: StatefulColor = Light.amber,
            orange: StatefulColor = Light.orange,
            red: StatefulColor = Light.red
        ): Colors = Colors(
            primary = primary,
            secondary = secondary,
            accent = accent,
            buttonPrimaryHover = buttonPrimaryHover,
            buttonPrimaryActive = buttonPrimaryActive,
            standard = standard,
            faint = faint,
            disabled = disabled,
            attention = attention,
            success = success,
            warning = warning,
            alert = alert,
            opacity1 = opacity1,
            opacity2 = opacity2,
            opacity3 = opacity3,
            gray = gray,
            rose = rose,
            pink = pink,
            fuchsia = fuchsia,
            purple = purple,
            violet = violet,
            indigo = indigo,
            blue = blue,
            sky = sky,
            cyan = cyan,
            teal = teal,
            emerald = emerald,
            green = green,
            lime = lime,
            yellow = yellow,
            amber = amber,
            orange = orange,
            red = red,
            isLight = IS_LIGHT
        )
    }

    object Dark {
        private val primary: Color = Color(0xfff7f5f2)
        private val secondary: Color = Color(0xff1e1919)
        private val accent: Color = Color(0xff3984ff)
        private val buttonPrimaryHover: Color = Color(0xff4d90ff)
        private val buttonPrimaryActive: Color = Color(0xff5d9aff)
        private val standard: StatefulColor = StatefulColor(
            text = Color(0xfff7f5f2),
            border = Color(142 / 255f, 139 / 255f, 135 / 255f, 0.5f),
            background = Color(0xff161313),
        )
        private val faint: StatefulColor = StatefulColor(
            text = Color(247 / 255f, 245 / 255f, 242 / 255f, 0.6f),
            border = Color(142 / 255f, 139 / 255f, 135 / 255f, 0.2f),
            background = Color(0xff242121),
        )
        private val disabled: StatefulColor = StatefulColor(
            text = Color(247 / 255f, 245 / 255f, 242 / 255f, 0.3f),
            border = Color(247 / 255f, 245 / 255f, 242 / 255f, 0.3f),
            background = Color(0xffd8d3cb),
        )
        private val attention: StatefulColor = StatefulColor(
            text = Color(0xff3984ff),
            border = Color(0xff3984ff),
            background = Color(57 / 255f, 132 / 255f, 255 / 255f, 0.4f),
        )
        private val success: StatefulColor = StatefulColor(
            text = Color(0xffb4dc19),
            border = Color(0xffb4dc19),
            background = Color(180 / 255f, 220 / 255f, 25 / 255f, 0.3f),
        )
        private val warning: StatefulColor = StatefulColor(
            text = Color(0xfffad24b),
            border = Color(0xfffad24b),
            background = Color(250 / 255f, 210 / 255f, 75 / 255f, 0.3f),
        )
        private val alert: StatefulColor = StatefulColor(
            text = Color(0xfffa551e),
            border = Color(0xfffa551e),
            background = Color(250 / 255f, 85 / 255f, 30 / 255f, 0.3f),
        )
        private val opacity1: Color = Color(142 / 255f, 139 / 255f, 135 / 255f, 0.16f)
        private val opacity2: Color = Color(142 / 255f, 139 / 255f, 135 / 255f, 0.24f)
        private val opacity3: Color = Color(142 / 255f, 139 / 255f, 135 / 255f, 0.32f)

        private val gray: StatefulColor = StatefulColor(
            text = Color(0xff9BA3AF),
            border = Color(0xff9BA3AF),
            background = Color(0xff282E36)
        )
        private val rose: StatefulColor = StatefulColor(
            text = Color(0xffFB7185),
            border = Color(0xffFB7185),
            background = Color(0xff4B2C38)
        )
        private val pink: StatefulColor = StatefulColor(
            text = Color(0xffF471B5),
            border = Color(0xffF471B5),
            background = Color(0xff4A2E43)
        )
        private val fuchsia: StatefulColor = StatefulColor(
            text = Color(0xffBF6ACF),
            border = Color(0xffBF6ACF),
            background = Color(0xff452D54)
        )
        private val purple: StatefulColor = StatefulColor(
            text = Color(0xffB57FEF),
            border = Color(0xffB57FEF),
            background = Color(0xff3B2F56)
        )
        private val violet: StatefulColor = StatefulColor(
            text = Color(0xff9F84EC),
            border = Color(0xff9F84EC),
            background = Color(0xff343156)
        )
        private val indigo: StatefulColor = StatefulColor(
            text = Color(0xff7B85EB),
            border = Color(0xff7B85EB),
            background = Color(0xff2D3255)
        )
        private val blue: StatefulColor = StatefulColor(
            text = Color(0xff5FA4F9),
            border = Color(0xff5FA4F9),
            background = Color(0xff263856)
        )
        private val sky: StatefulColor = StatefulColor(
            text = Color(0xff37BAF5),
            border = Color(0xff37BAF5),
            background = Color(0xff224053)
        )
        private val cyan: StatefulColor = StatefulColor(
            text = Color(0xff2CADC4),
            border = Color(0xff2CADC4),
            background = Color(0xff244345)
        )
        private val teal: StatefulColor = StatefulColor(
            text = Color(0xff2CD4BF),
            border = Color(0xff2CD4BF),
            background = Color(0xff22434F)
        )
        private val emerald: StatefulColor = StatefulColor(
            text = Color(0xff35B286),
            border = Color(0xff35B286),
            background = Color(0xff24433F)
        )
        private val green: StatefulColor = StatefulColor(
            text = Color(0xff49DE80),
            border = Color(0xff49DE80),
            background = Color(0xff254638)
        )
        private val lime: StatefulColor = StatefulColor(
            text = Color(0xff9ADA37),
            border = Color(0xff9ADA37),
            background = Color(0xff34462F)
        )
        private val yellow: StatefulColor = StatefulColor(
            text = Color(0xffF8CB15),
            border = Color(0xffF8CB15),
            background = Color(0xff48422E)
        )
        private val amber: StatefulColor = StatefulColor(
            text = Color(0xffFBBE24),
            border = Color(0xffFBBE24),
            background = Color(0xff4B3D2E)
        )
        private val orange: StatefulColor = StatefulColor(
            text = Color(0xffFB923C),
            border = Color(0xffFB923C),
            background = Color(0xff4B362E)
        )
        private val red: StatefulColor = StatefulColor(
            text = Color(0xffF16F6F),
            border = Color(0xffF16F6F),
            background = Color(0xff4A2D33)
        )

        private const val IS_LIGHT: Boolean = false

        fun create(
            primary: Color = Dark.primary,
            secondary: Color = Dark.secondary,
            accent: Color = Dark.accent,
            buttonPrimaryHover: Color = Dark.buttonPrimaryHover,
            buttonPrimaryActive: Color = Dark.buttonPrimaryActive,
            standard: StatefulColor = Dark.standard,
            faint: StatefulColor = Dark.faint,
            disabled: StatefulColor = Dark.disabled,
            attention: StatefulColor = Dark.attention,
            success: StatefulColor = Dark.success,
            warning: StatefulColor = Dark.warning,
            alert: StatefulColor = Dark.alert,
            opacity1: Color = Dark.opacity1,
            opacity2: Color = Dark.opacity2,
            opacity3: Color = Dark.opacity3,
            gray: StatefulColor = Dark.gray,
            rose: StatefulColor = Dark.rose,
            pink: StatefulColor = Dark.pink,
            fuchsia: StatefulColor = Dark.fuchsia,
            purple: StatefulColor = Dark.purple,
            violet: StatefulColor = Dark.violet,
            indigo: StatefulColor = Dark.indigo,
            blue: StatefulColor = Dark.blue,
            sky: StatefulColor = Dark.sky,
            cyan: StatefulColor = Dark.cyan,
            teal: StatefulColor = Dark.teal,
            emerald: StatefulColor = Dark.emerald,
            green: StatefulColor = Dark.green,
            lime: StatefulColor = Dark.lime,
            yellow: StatefulColor = Dark.yellow,
            amber: StatefulColor = Dark.amber,
            orange: StatefulColor = Dark.orange,
            red: StatefulColor = Dark.red
        ): Colors = Colors(
            primary = primary,
            secondary = secondary,
            accent = accent,
            buttonPrimaryHover = buttonPrimaryHover,
            buttonPrimaryActive = buttonPrimaryActive,
            standard = standard,
            faint = faint,
            disabled = disabled,
            attention = attention,
            success = success,
            warning = warning,
            alert = alert,
            opacity1 = opacity1,
            opacity2 = opacity2,
            opacity3 = opacity3,
            gray = gray,
            rose = rose,
            pink = pink,
            fuchsia = fuchsia,
            purple = purple,
            violet = violet,
            indigo = indigo,
            blue = blue,
            sky = sky,
            cyan = cyan,
            teal = teal,
            emerald = emerald,
            green = green,
            lime = lime,
            yellow = yellow,
            amber = amber,
            orange = orange,
            red = red,
            isLight = IS_LIGHT
        )
    }

    object Accent {
        private val zen: Color = Color(0xFF14C8EB)
        private val ocean: Color = Color(0xFF007891)
        private val sunset: Color = Color(0xFFFA551E)
        private val crimson: Color = Color(0xFF9B0032)
        private val tangerine: Color = Color(0xFFFF8C19)
        private val rust: Color = Color(0xFFBE4B0A)
        private val lime: Color = Color(0xFFB4DC19)
        private val canopy: Color = Color(0xFF0F503C)
        private val cloud: Color = Color(0xFFB4C8E1)
        private val navy: Color = Color(0xFF283750)
        private val orchid: Color = Color(0xFFC8AFF0)
        private val plum: Color = Color(0xFF78286E)
        private val pink: Color = Color(0xFFFFAFA5)
        private val azalea: Color = Color(0xFFCD2F7B)
        private val banana: Color = Color(0xFFFAD24B)
        private val gold: Color = Color(0xFF9B6400)

        private val lightColors = listOf(ocean, crimson, rust, canopy, navy, plum, azalea, gold)
        private val darkColors = listOf(zen, sunset, tangerine, lime, cloud, orchid, pink, banana)
        private val lightContentColor = Color(0xFFF7F5F2)
        private val darkContentColor = Color(0xFF1E1919)

        fun contentColor(isLight: Boolean) = when (isLight) {
            true -> lightContentColor
            false -> darkContentColor
        }

        fun get(isLight: Boolean) = when (isLight) {
            true -> lightColors
            false -> darkColors
        }
    }
}