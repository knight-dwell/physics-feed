package io.github.knightdwell.physicsfeed.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFFADC6FF), onPrimary = Color(0xFF102F60),
    primaryContainer = Color(0xFF2B4678), onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFF6C177), onSecondary = Color(0xFF3A2600),
    secondaryContainer = Color(0xFF4A3A1F), onSecondaryContainer = Color(0xFFFFE0B2),
    tertiary = Color(0xFFDEBCDF), tertiaryContainer = Color(0xFF3D2A4A), onTertiaryContainer = Color(0xFFF3DCF6),
    background = Color(0xFF111318), onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF282A2F), onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainer = Color(0xFF1E1F25), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    outline = Color(0xFF8E9099), outlineVariant = Color(0xFF44474F), error = Color(0xFFFFB4AB),
)

private val Light = lightColorScheme(
    primary = Color(0xFF415F91), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF7A5900), secondaryContainer = Color(0xFFFFE0B2), onSecondaryContainer = Color(0xFF6B4300),
    tertiaryContainer = Color(0xFFF3DCF6), onTertiaryContainer = Color(0xFF5A2D63),
    background = Color(0xFFF9F9FF), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE1E2E8), onSurfaceVariant = Color(0xFF44474E),
    surfaceContainerLow = Color(0xFFF3F3FA), surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE), surfaceContainerHighest = Color(0xFFE1E2E8),
    outline = Color(0xFF74777F), outlineVariant = Color(0xFFC4C6D0), error = Color(0xFFBA1A1A),
)

/** Source chip colours: arXiv red, APS blue, Nature green, Wiley amber. */
fun sourceColors(source: String, dark: Boolean): Pair<Color, Color> = when {
    source == "arXiv" -> if (dark) Color(0xFF5C2B2B) to Color(0xFFFFB4AB) else Color(0xFFFFDAD6) to Color(0xFF8C1D18)
    source.startsWith("PR") || source.startsWith("Rev. Mod") ->
        if (dark) Color(0xFF22385E) to Color(0xFFADC6FF) else Color(0xFFD6E3FF) to Color(0xFF1F3A66)
    "Nature" in source || "npj" in source || "Scientific" in source || "Communications" in source ->
        if (dark) Color(0xFF1F4A3A) to Color(0xFFA8D5A2) else Color(0xFFCDECCB) to Color(0xFF1F4D2B)
    "Adv" in source || "Wiley" in source -> if (dark) Color(0xFF4A3A1F) to Color(0xFFF6C177) else Color(0xFFFFE0B2) to Color(0xFF6B4300)
    else -> if (dark) Color(0xFF33353A) to Color(0xFFE2E2E9) else Color(0xFFE1E2E8) to Color(0xFF191C20)
}

@Composable
fun isDark(theme: String): Boolean = when (theme) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun PhysicsFeedTheme(theme: String, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isDark(theme)) Dark else Light, content = content)
}
