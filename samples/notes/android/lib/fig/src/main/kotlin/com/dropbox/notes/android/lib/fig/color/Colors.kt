package com.dropbox.notes.android.lib.fig.color

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.graphics.Color

@Stable
class Colors(
    primary: Color,
    secondary: Color,
    accent: Color,
    buttonPrimaryHover: Color,
    buttonPrimaryActive: Color,
    standard: StatefulColor,
    faint: StatefulColor,
    disabled: StatefulColor,
    attention: StatefulColor,
    success: StatefulColor,
    warning: StatefulColor,
    alert: StatefulColor,
    opacity1: Color,
    opacity2: Color,
    opacity3: Color,

    gray: StatefulColor,
    rose: StatefulColor,
    pink: StatefulColor,
    fuchsia: StatefulColor,
    purple: StatefulColor,
    violet: StatefulColor,
    indigo: StatefulColor,
    blue: StatefulColor,
    sky: StatefulColor,
    cyan: StatefulColor,
    teal: StatefulColor,
    emerald: StatefulColor,
    green: StatefulColor,
    lime: StatefulColor,
    yellow: StatefulColor,
    amber: StatefulColor,
    orange: StatefulColor,
    red: StatefulColor,

    isLight: Boolean,
) {

    var primary: Color by mutableStateOf(primary, structuralEqualityPolicy())
        internal set
    var secondary: Color by mutableStateOf(secondary, structuralEqualityPolicy())
        internal set
    var accent: Color by mutableStateOf(accent, structuralEqualityPolicy())
        internal set

    var buttonPrimaryHover: Color by mutableStateOf(buttonPrimaryHover, structuralEqualityPolicy())
        internal set
    var buttonPrimaryActive: Color by mutableStateOf(buttonPrimaryActive, structuralEqualityPolicy())
        internal set

    var standard: StatefulColor by mutableStateOf(standard, structuralEqualityPolicy())
        internal set
    var faint: StatefulColor by mutableStateOf(faint, structuralEqualityPolicy())
        internal set
    var disabled: StatefulColor by mutableStateOf(disabled, structuralEqualityPolicy())
        internal set
    var attention: StatefulColor by mutableStateOf(attention, structuralEqualityPolicy())
        internal set
    var success: StatefulColor by mutableStateOf(success, structuralEqualityPolicy())
        internal set
    var warning: StatefulColor by mutableStateOf(warning, structuralEqualityPolicy())
        internal set
    var alert: StatefulColor by mutableStateOf(alert, structuralEqualityPolicy())
        internal set

    var gray: StatefulColor by mutableStateOf(gray, structuralEqualityPolicy())
        internal set
    var rose: StatefulColor by mutableStateOf(rose, structuralEqualityPolicy())
        internal set
    var pink: StatefulColor by mutableStateOf(pink, structuralEqualityPolicy())
        internal set
    var fuchsia: StatefulColor by mutableStateOf(fuchsia, structuralEqualityPolicy())
        internal set
    var purple: StatefulColor by mutableStateOf(purple, structuralEqualityPolicy())
        internal set
    var violet: StatefulColor by mutableStateOf(violet, structuralEqualityPolicy())
        internal set
    var indigo: StatefulColor by mutableStateOf(indigo, structuralEqualityPolicy())
        internal set
    var blue: StatefulColor by mutableStateOf(blue, structuralEqualityPolicy())
        internal set
    var sky: StatefulColor by mutableStateOf(sky, structuralEqualityPolicy())
        internal set
    var cyan: StatefulColor by mutableStateOf(cyan, structuralEqualityPolicy())
        internal set
    var teal: StatefulColor by mutableStateOf(teal, structuralEqualityPolicy())
        internal set
    var emerald: StatefulColor by mutableStateOf(emerald, structuralEqualityPolicy())
        internal set
    var green: StatefulColor by mutableStateOf(green, structuralEqualityPolicy())
        internal set
    var lime: StatefulColor by mutableStateOf(lime, structuralEqualityPolicy())
        internal set
    var yellow: StatefulColor by mutableStateOf(yellow, structuralEqualityPolicy())
        internal set
    var amber: StatefulColor by mutableStateOf(amber, structuralEqualityPolicy())
        internal set
    var orange: StatefulColor by mutableStateOf(orange, structuralEqualityPolicy())
        internal set
    var red: StatefulColor by mutableStateOf(red, structuralEqualityPolicy())
        internal set

    var opacity1: Color by mutableStateOf(opacity1, structuralEqualityPolicy())
        internal set
    var opacity2: Color by mutableStateOf(opacity1, structuralEqualityPolicy())
        internal set
    var opacity3: Color by mutableStateOf(opacity1, structuralEqualityPolicy())
        internal set
    var isLight: Boolean by mutableStateOf(isLight, structuralEqualityPolicy())
        internal set

    fun copy(
        primary: Color = this.primary,
        secondary: Color = this.secondary,
        accent: Color = this.accent,
        buttonPrimaryHover: Color = this.buttonPrimaryHover,
        buttonPrimaryActive: Color = this.buttonPrimaryActive,
        standard: StatefulColor = this.standard,
        faint: StatefulColor = this.faint,
        disabled: StatefulColor = this.disabled,
        attention: StatefulColor = this.attention,
        success: StatefulColor = this.success,
        warning: StatefulColor = this.warning,
        alert: StatefulColor = this.alert,
        gray: StatefulColor = this.gray,
        rose: StatefulColor = this.rose,
        pink: StatefulColor = this.pink,
        fuchsia: StatefulColor = this.fuchsia,
        purple: StatefulColor = this.purple,
        violet: StatefulColor = this.violet,
        indigo: StatefulColor = this.indigo,
        blue: StatefulColor = this.blue,
        sky: StatefulColor = this.sky,
        cyan: StatefulColor = this.cyan,
        teal: StatefulColor = this.teal,
        emerald: StatefulColor = this.emerald,
        green: StatefulColor = this.green,
        lime: StatefulColor = this.lime,
        yellow: StatefulColor = this.yellow,
        amber: StatefulColor = this.amber,
        orange: StatefulColor = this.orange,
        red: StatefulColor = this.red,
        opacity1: Color = this.opacity1,
        opacity2: Color = this.opacity2,
        opacity3: Color = this.opacity3,
        isLight: Boolean = this.isLight,
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
        opacity1 = opacity1,
        opacity2 = opacity2,
        opacity3 = opacity3,
        isLight = isLight,
    )

    override fun toString(): String = "Colors(" +
        "primary=$primary, " +
        "secondary=$secondary, " +
        "accent=$accent, " +
        "buttonPrimaryHover=$buttonPrimaryHover, " +
        "buttonPrimaryActive=$buttonPrimaryActive, " +
        "standard=$standard, " +
        "faint=$faint, " +
        "disabled=$disabled, " +
        "attention=$attention, " +
        "success=$success, " +
        "warning=$warning, " +
        "alert=$alert, " +
        "opacity1=$opacity1, " +
        "opacity2=$opacity2, " +
        "opacity3=$opacity3, " +
        "isLight=$isLight" +
        ")"
}
