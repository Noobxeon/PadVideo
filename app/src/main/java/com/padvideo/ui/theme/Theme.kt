package com.padvideo.ui.theme

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
import com.padvideo.data.model.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = CinemaPrimaryDark,
    onPrimary = CinemaOnPrimaryDark,
    primaryContainer = CinemaPrimaryContainerDark,
    onPrimaryContainer = CinemaOnPrimaryContainerDark,
    secondary = CinemaSecondaryDark,
    onSecondary = CinemaOnSecondaryDark,
    secondaryContainer = CinemaSecondaryContainerDark,
    onSecondaryContainer = CinemaOnSecondaryContainerDark,
    tertiary = CinemaTertiaryDark,
    background = CinemaSurfaceDark,
    surface = CinemaSurfaceDark,
    surfaceVariant = CinemaSurfaceContainerDark,
    surfaceContainer = CinemaSurfaceContainerDark,
    surfaceContainerHigh = CinemaSurfaceContainerHighDark
)

private val LightColorScheme = lightColorScheme(
    primary = CinemaPrimaryLight,
    onPrimary = CinemaOnPrimaryLight,
    primaryContainer = CinemaPrimaryContainerLight,
    onPrimaryContainer = CinemaOnPrimaryContainerLight,
    secondary = CinemaSecondaryLight,
    onSecondary = CinemaOnSecondaryLight,
    secondaryContainer = CinemaSecondaryContainerLight,
    onSecondaryContainer = CinemaOnSecondaryContainerLight,
    background = CinemaSurfaceLight,
    surface = CinemaSurfaceLight,
    surfaceVariant = CinemaSurfaceContainerLight,
    surfaceContainer = CinemaSurfaceContainerLight
)

@Composable
fun PadVideoTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        ThemeMode.DARK, ThemeMode.AMOLED_BLACK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

    val context = LocalContext.current
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDark -> DarkColorScheme
        else -> LightColorScheme
    }

    val finalScheme = if (themeMode == ThemeMode.AMOLED_BLACK) {
        baseScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0A0A0A),
            surfaceContainer = Color(0xFF121212)
        )
    } else {
        baseScheme
    }

    MaterialTheme(
        colorScheme = finalScheme,
        typography = PadVideoTypography,
        content = content
    )
}
