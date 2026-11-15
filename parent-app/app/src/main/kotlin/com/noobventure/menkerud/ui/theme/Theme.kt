package com.noobventure.menkerud.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The kiosk's colours (the `:root` block of index.html), so the app and the screen at home look like one thing. */
object MenkerudColors {
    val Night = Color(0xFF07162F)
    val Glass = Color(0x8C0B1B3D)           // a panel on the night background
    val GlassStrong = Color(0xD10C1A3A)
    val GlassLine = Color(0x24FFFFFF)
    val Text = Color(0xFFFFFFFF)
    val TextSoft = Color(0xC7FFFFFF)
    val TextMuted = Color(0x8CFFFFFF)
    val Pink = Color(0xFFFF5C96)
    val PinkDeep = Color(0xFFD91669)
    val Blue = Color(0xFF19B9FF)
    val BlueDeep = Color(0xFF0872D1)
    val Green = Color(0xFF25E08F)
    val GreenDeep = Color(0xFF05885D)
}

private val Scheme = darkColorScheme(
    primary = MenkerudColors.Blue,
    onPrimary = MenkerudColors.Text,
    secondary = MenkerudColors.Pink,
    onSecondary = MenkerudColors.Text,
    tertiary = MenkerudColors.Green,
    onTertiary = MenkerudColors.Night,
    background = MenkerudColors.Night,
    onBackground = MenkerudColors.Text,
    surface = MenkerudColors.Night,
    onSurface = MenkerudColors.Text,
    surfaceVariant = Color(0xFF0C1A3A),
    onSurfaceVariant = MenkerudColors.TextSoft,
    outline = MenkerudColors.GlassLine,
)

/** Dark only, like the kiosk. */
@Composable
fun MenkerudTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
