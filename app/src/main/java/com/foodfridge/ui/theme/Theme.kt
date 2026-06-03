package com.foodfridge.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    onPrimary = OnPrimaryBlue,
    primaryContainer = OnPrimaryBlueContainer,
    onPrimaryContainer = OnPrimaryBlue,
    secondary = PrimaryBlueContainer,
    onSecondary = OnPrimaryBlueContainer,
    tertiary = StatusSuccess,
    onTertiary = OnPrimaryBlue,
    background = BackgroundBlueWhite,
    onBackground = TextPrimary,
    surface = SurfaceBlueWhite,
    onSurface = TextPrimary,
    surfaceVariant = HoverBlue,
    onSurfaceVariant = TextSecondary,
    error = StatusError,
    onError = OnPrimaryBlue,
    errorContainer = StatusErrorContainer,
    onErrorContainer = StatusError,
    outline = BorderGray
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = OnPrimaryBlue,
    primaryContainer = PrimaryBlueContainer,
    onPrimaryContainer = OnPrimaryBlueContainer,
    secondary = HoverBlue,
    onSecondary = OnPrimaryBlueContainer,
    tertiary = StatusSuccess,
    onTertiary = OnPrimaryBlue,
    background = BackgroundBlueWhite,
    onBackground = TextPrimary,
    surface = SurfaceBlueWhite,
    onSurface = TextPrimary,
    surfaceVariant = HoverBlue,
    onSurfaceVariant = TextSecondary,
    error = StatusError,
    onError = OnPrimaryBlue,
    errorContainer = StatusErrorContainer,
    onErrorContainer = StatusError,
    outline = BorderGray

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun SmartScaleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Keep design-system colors stable across devices.
    dynamicColor: Boolean = false,
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
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
    ) {
        content()
    }
}
