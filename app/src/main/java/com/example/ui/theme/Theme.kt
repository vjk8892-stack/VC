package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = SkyBlue60,
    onPrimary = OnPrimaryDark,
    primaryContainer = SkyBlue80,
    onPrimaryContainer = OnPrimaryDark,
    secondary = TealAccent,
    onSecondary = OnPrimaryDark,
    background = DarkCanvas,
    onBackground = TextMain,
    surface = DarkSurface,
    onSurface = TextMain,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextMuted,
    error = RoseError,
    onError = OnPrimaryDark
)

private val LightColorScheme = darkColorScheme(
    primary = SkyBlue60,
    onPrimary = OnPrimaryDark,
    primaryContainer = SkyBlue80,
    onPrimaryContainer = OnPrimaryDark,
    secondary = TealAccent,
    onSecondary = OnPrimaryDark,
    background = DarkCanvas,
    onBackground = TextMain,
    surface = DarkSurface,
    onSurface = TextMain,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextMuted,
    error = RoseError,
    onError = OnPrimaryDark
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else DarkColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
