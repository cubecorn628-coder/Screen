// File: app/src/main/java/com/example/ui/theme/Theme.kt
package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = SlatePrimaryDark,
    secondary = SlateSecondaryDark,
    background = SlateBackgroundDark,
    surface = SlateSurfaceDark,
    onBackground = SlateOnSurfaceDark,
    onSurface = SlateOnSurfaceDark,
    surfaceVariant = SlateContainerDark
)

private val LightColorScheme = lightColorScheme(
    primary = SlatePrimaryLight,
    secondary = SlateSecondaryLight,
    background = SlateBackgroundLight,
    surface = SlateSurfaceLight,
    onBackground = SlateOnSurfaceLight,
    onSurface = SlateOnSurfaceLight
)

@Composable
fun PhotoStandTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// Backward compatibility alias for template references
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    PhotoStandTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
}
