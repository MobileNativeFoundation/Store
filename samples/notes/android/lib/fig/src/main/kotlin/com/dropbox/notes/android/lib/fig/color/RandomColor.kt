package com.dropbox.notes.android.lib.fig.color

import kotlin.random.Random

fun randomColor(colors: Colors): StatefulColor {
    val allColors = listOf(
        colors.gray,
        colors.rose,
        colors.pink,
        colors.fuchsia,
        colors.purple,
        colors.violet,
        colors.indigo,
        colors.blue,
        colors.sky,
        colors.cyan,
        colors.teal,
        colors.green,
        colors.lime,
        colors.yellow,
        colors.amber,
        colors.orange,
        colors.red
    )

    val randomInt = Random.nextInt(0, allColors.lastIndex)

    return allColors[randomInt]
}