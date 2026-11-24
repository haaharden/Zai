package com.self.zai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.self.zai.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = SignalCobaltLight,
    onPrimary = Color.White,
    primaryContainer = CobaltContainerLight,
    onPrimaryContainer = Color(0xFF10308A),
    secondary = LiveEmerald,
    onSecondary = Color.White,
    tertiary = CautionAmber,
    onTertiary = Color.White,
    background = PaperGround,
    onBackground = CarbonInk,
    surface = PureSheet,
    onSurface = CarbonInk,
    surfaceVariant = PaperSubtle,
    onSurfaceVariant = SlateMuted,
    surfaceContainerLow = Color(0xFFF1F3F7),
    surfaceContainer = Color(0xFFEBEEF3),
    surfaceContainerHigh = Color(0xFFE3E7EE),
    outline = Color(0xFFBDC4D0),
    outlineVariant = HairlineLight,
    error = StopCrimson,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = SignalCobaltDark,
    onPrimary = Color(0xFF071636),
    primaryContainer = CobaltContainerDark,
    onPrimaryContainer = Color(0xFFC8DAFF),
    secondary = LiveEmeraldDark,
    onSecondary = Color(0xFF05271D),
    tertiary = CautionAmberDark,
    onTertiary = Color(0xFF2B1B00),
    background = GraphiteGround,
    onBackground = ChalkInk,
    surface = GraphiteSurface,
    onSurface = ChalkInk,
    surfaceVariant = GraphiteSubtle,
    onSurfaceVariant = SteelMuted,
    surfaceContainerLow = Color(0xFF12171E),
    surfaceContainer = Color(0xFF181E27),
    surfaceContainerHigh = Color(0xFF202834),
    outline = Color(0xFF475264),
    outlineVariant = HairlineDark,
    error = StopCrimsonDark,
    onError = Color(0xFF330808),
)

private val AmoledColors = DarkColors.copy(
    background = OledGround,
    surface = OledSurface,
    surfaceVariant = OledSubtle,
    surfaceContainerLow = Color(0xFF0A0D12),
    surfaceContainer = Color(0xFF10141B),
    surfaceContainerHigh = Color(0xFF181E28),
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
