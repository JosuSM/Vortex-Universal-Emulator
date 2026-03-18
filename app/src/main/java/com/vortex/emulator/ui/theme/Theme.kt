package com.vortex.emulator.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val VortexDarkColorScheme = darkColorScheme(
    primary = VortexCyan,
    onPrimary = VortexSurface,
    primaryContainer = VortexCyanDark,
    onPrimaryContainer = VortexCyanLight,

    secondary = VortexPurple,
    onSecondary = VortexSurface,
    secondaryContainer = VortexPurpleDark,
    onSecondaryContainer = VortexPurpleLight,

    tertiary = VortexMagenta,
    onTertiary = VortexSurface,
    tertiaryContainer = VortexMagentaDark,
    onTertiaryContainer = VortexMagentaLight,

    error = VortexRed,
    onError = VortexSurface,

    background = VortexSurface,
    onBackground = VortexOnSurface,

    surface = VortexSurface,
    onSurface = VortexOnSurface,
    surfaceVariant = VortexSurfaceVariant,
    onSurfaceVariant = VortexOnSurfaceVariant,

    surfaceContainer = VortexSurfaceContainer,
    surfaceContainerHigh = VortexSurfaceContainerHigh,
    surfaceContainerHighest = VortexSurfaceContainerHighest,

    outline = VortexOnSurfaceVariant,
    outlineVariant = VortexSurfaceContainerHigh
)

@Composable
fun VortexTheme(
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicDarkColorScheme(LocalContext.current)
        }
        else -> VortexDarkColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // API 36+ enforces edge-to-edge; status/nav bar colors are ignored
            if (Build.VERSION.SDK_INT < 36) {
                @Suppress("DEPRECATION")
                window.statusBarColor = VortexSurface.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = VortexSurface.toArgb()
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VortexTypography,
        content = content
    )
}
