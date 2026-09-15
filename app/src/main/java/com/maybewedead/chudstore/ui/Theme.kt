package com.maybewedead.chudstore.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// -----------------------------------------------------------------------
// Theme.kt — палитра сознательно сдержанная, в духе iOS Settings/App
// Store: почти монохромная, один акцентный синий, много воздуха между
// элементами (см. ScreenPadding в компонентах списков). Никаких Material
// You dynamic-color — это специально приглушённый, "нейтральный" вид,
// не подстраивающийся под обои пользователя, как это делает TrollStore
// на iOS (там тоже никакой адаптации под system accent color).
// -----------------------------------------------------------------------

private val IosBlue = Color(0xFF0A84FF)
private val IosRed = Color(0xFFFF453A)
private val IosGreen = Color(0xFF32D74B)

private val LightColors = lightColorScheme(
    primary = IosBlue,
    error = IosRed,
    background = Color(0xFFF2F2F7), // iOS grouped table view background
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = IosBlue,
    error = IosRed,
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E), // iOS dark grouped cell background
)

val SuccessColor: Color
    @Composable get() = IosGreen

@Composable
fun ChudStoreTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
