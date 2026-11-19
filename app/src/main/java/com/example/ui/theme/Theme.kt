package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Immutable
data class StudioSemanticColors(
  val recordAccent: Color,
  val recordDeep: Color,
  val recordContainer: Color,
  val pausedAccent: Color,
  val pausedContainer: Color,
  val readyAccent: Color,
  val readyContainer: Color,
)

val LocalStudioColors = staticCompositionLocalOf {
  StudioSemanticColors(
    recordAccent = StudioCrimson,
    recordDeep = StudioCrimsonDeep,
    recordContainer = StudioCrimsonContainerDark,
    pausedAccent = StudioAmber,
    pausedContainer = StudioAmberContainerDark,
    readyAccent = StudioEmerald,
    readyContainer = StudioEmeraldContainerDark,
  )
}

private val StudioDarkColorScheme =
  darkColorScheme(
    primary = StudioCrimson,
    onPrimary = Color.White,
    primaryContainer = StudioCrimsonContainerDark,
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = StudioSteelBlueLight,
    onSecondary = Color(0xFF0A1D3D),
    secondaryContainer = Color(0xFF1E3254),
    onSecondaryContainer = Color(0xFFD8E5FF),
    tertiary = StudioEmerald,
    onTertiary = Color(0xFF04261B),
    background = StudioDarkBackground,
    onBackground = StudioDarkOnSurface,
    surface = StudioDarkSurface,
    onSurface = StudioDarkOnSurface,
    surfaceVariant = StudioDarkSurfaceVariant,
    onSurfaceVariant = StudioDarkOnSurfaceVariant,
    outline = StudioDarkOutline,
    outlineVariant = Color(0xFF232836),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
  )

private val StudioLightColorScheme =
  lightColorScheme(
    primary = StudioCrimsonDeep,
    onPrimary = Color.White,
    primaryContainer = StudioCrimsonContainerLight,
    onPrimaryContainer = Color(0xFF7F1D1D),
    secondary = StudioSteelBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF1E3A8A),
    tertiary = Color(0xFF059669),
    onTertiary = Color.White,
    background = StudioLightBackground,
    onBackground = StudioLightOnSurface,
    surface = StudioLightSurface,
    onSurface = StudioLightOnSurface,
    surfaceVariant = StudioLightSurfaceVariant,
    onSurfaceVariant = StudioLightOnSurfaceVariant,
    outline = StudioLightOutline,
    outlineVariant = Color(0xFFE5E9F0),
    error = StudioCrimsonDeep,
    onError = Color.White,
  )

@Composable
fun FrameCutTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> StudioDarkColorScheme
      else -> StudioLightColorScheme
    }

  val semanticColors =
    if (darkTheme) {
      StudioSemanticColors(
        recordAccent = StudioCrimson,
        recordDeep = StudioCrimsonDeep,
        recordContainer = StudioCrimsonContainerDark,
        pausedAccent = StudioAmber,
        pausedContainer = StudioAmberContainerDark,
        readyAccent = StudioEmerald,
        readyContainer = StudioEmeraldContainerDark,
      )
    } else {
      StudioSemanticColors(
        recordAccent = StudioCrimsonDeep,
        recordDeep = StudioCrimsonDeep,
        recordContainer = StudioCrimsonContainerLight,
        pausedAccent = StudioAmber,
        pausedContainer = StudioAmberContainerLight,
        readyAccent = Color(0xFF059669),
        readyContainer = StudioEmeraldContainerLight,
      )
    }

  CompositionLocalProvider(LocalStudioColors provides semanticColors) {
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
  }
}
