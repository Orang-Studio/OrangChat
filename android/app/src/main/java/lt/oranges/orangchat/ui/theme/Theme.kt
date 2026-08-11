package lt.oranges.orangchat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemePreference { SYSTEM, DARK, LIGHT }

private fun OrangColors.toMaterialScheme() = if (isDark) {
    darkColorScheme(
        primary = primary,
        onPrimary = inkOnPrimary,
        primaryContainer = primarySoft,
        onPrimaryContainer = primary,
        secondary = inkSecondary,
        onSecondary = ink,
        background = surface1,
        onBackground = ink,
        surface = surface2,
        onSurface = ink,
        surfaceVariant = surface3,
        onSurfaceVariant = inkSecondary,
        surfaceContainer = surface3,
        surfaceContainerHigh = surface4,
        surfaceContainerHighest = surface4,
        surfaceContainerLow = surface1,
        surfaceContainerLowest = surface0,
        outline = borderStrong,
        outlineVariant = border,
        error = danger,
        onError = ink,
    )
} else {
    lightColorScheme(
        primary = primary,
        onPrimary = inkOnPrimary,
        primaryContainer = primarySoft,
        onPrimaryContainer = primary,
        secondary = inkSecondary,
        onSecondary = ink,
        background = surface1,
        onBackground = ink,
        surface = surface2,
        onSurface = ink,
        surfaceVariant = surface3,
        onSurfaceVariant = inkSecondary,
        surfaceContainer = surface3,
        surfaceContainerHigh = surface4,
        surfaceContainerHighest = surface4,
        surfaceContainerLow = surface1,
        surfaceContainerLowest = surface0,
        outline = borderStrong,
        outlineVariant = border,
        error = danger,
        onError = surface4,
    )
}

@Composable
fun OrangChatTheme(
    preference: ThemePreference = ThemePreference.DARK,
    content: @Composable () -> Unit,
) {
    val dark = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
    }
    val colors = if (dark) DarkOrangColors else LightOrangColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.surface0.toArgb()
            window.navigationBarColor = colors.surface0.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalOrangColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            typography = OrangTypography,
            shapes = OrangShapes,
            content = content,
        )
    }
}
