package com.eightbee.app.ui.theme

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
    primary = MD3_Dark_Primary,
    onPrimary = MD3_Dark_OnPrimary,
    primaryContainer = MD3_Dark_PrimaryContainer,
    onPrimaryContainer = MD3_Dark_OnPrimaryContainer,
    secondary = MD3_Dark_Secondary,
    onSecondary = MD3_Dark_OnSecondary,
    secondaryContainer = MD3_Dark_SecondaryContainer,
    onSecondaryContainer = MD3_Dark_OnSecondaryContainer,
    surface = MD3_Dark_Surface,
    onSurface = MD3_Dark_OnSurface,
    surfaceVariant = MD3_Dark_SurfaceVariant,
    onSurfaceVariant = MD3_Dark_OnSurfaceVariant
)

private val LightColorScheme = lightColorScheme(
    primary = MD3_Light_Primary,
    onPrimary = MD3_Light_OnPrimary,
    primaryContainer = MD3_Light_PrimaryContainer,
    onPrimaryContainer = MD3_Light_OnPrimaryContainer,
    secondary = MD3_Light_Secondary,
    onSecondary = MD3_Light_OnSecondary,
    secondaryContainer = MD3_Light_SecondaryContainer,
    onSecondaryContainer = MD3_Light_OnSecondaryContainer,
    surface = MD3_Light_Surface,
    onSurface = MD3_Light_OnSurface,
    surfaceVariant = MD3_Light_SurfaceVariant,
    onSurfaceVariant = MD3_Light_OnSurfaceVariant
)

@Composable
fun EightBeeAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
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
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
