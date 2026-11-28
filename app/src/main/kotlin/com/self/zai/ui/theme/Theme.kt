package com.self.zai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.self.zai.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = OpticalBlueLight,
    onPrimary = Color.White,
    primaryContainer = OpticalBlueContainerLight,
    onPrimaryContainer = OpticalBlueOnContainerLight,
    secondary = EmeraldReadyLight,
    onSecondary = Color.White,
    tertiary = AmberNoticeLight,
    onTertiary = Color.White,
    background = FrostGround,
    onBackground = GraphiteInk,
    surface = PureGlassSheet,
    onSurface = GraphiteInk,
    surfaceVariant = SilverTonal,
    onSurfaceVariant = SlateMuted,
    surfaceContainerLow = SilverTonalSoft,
    surfaceContainer = SilverTonal,
    surfaceContainerHigh = SilverTonalHigh,
    outline = SilverOutline,
    outlineVariant = SilverHairline,
    error = CrimsonStopLight,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = OpticalBlueDark,
    onPrimary = Color.White,
    primaryContainer = OpticalBlueContainerDark,
    onPrimaryContainer = OpticalBlueOnContainerDark,
    secondary = EmeraldReadyDark,
    onSecondary = Color(0xFF04210D),
    tertiary = AmberNoticeDark,
    onTertiary = Color(0xFF2B1800),
    background = SpaceGround,
    onBackground = TitaniumInk,
    surface = SpaceSheet,
    onSurface = TitaniumInk,
    surfaceVariant = SpaceTonal,
    onSurfaceVariant = TitaniumMuted,
    surfaceContainerLow = SpaceTonalSoft,
    surfaceContainer = SpaceTonal,
    surfaceContainerHigh = SpaceTonalHigh,
    outline = SpaceOutline,
    outlineVariant = SpaceHairline,
    error = CrimsonStopDark,
    onError = Color.White,
)

private val AmoledColors = DarkColors.copy(
    background = OledGround,
    surface = OledSheet,
    surfaceVariant = OledTonal,
    surfaceContainerLow = OledTonalSoft,
    surfaceContainer = OledTonal,
    surfaceContainerHigh = OledTonalHigh,
    outlineVariant = OledHairline,
)

@Composable
fun ZaiTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val scheme = when (themeMode) {
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.AMOLED -> AmoledColors
        ThemeMode.SYSTEM -> if (systemDark) DarkColors else LightColors
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = ZaiTypography,
        content = content,
    )
}
