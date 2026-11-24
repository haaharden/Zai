package com.self.zai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.self.zai.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = GlacierIndigoLight,
    onPrimary = Color.White,
    primaryContainer = GlacierContainerLight,
    onPrimaryContainer = Color(0xFF1A3185),
    secondary = JadeReadyLight,
    onSecondary = Color.White,
    tertiary = AmberNoticeLight,
    onTertiary = Color.White,
    background = PorcelainGround,
    onBackground = Inkstone,
    surface = PaperSheet,
    onSurface = Inkstone,
    surfaceVariant = PaperTonal,
    onSurfaceVariant = StoneMuted,
    surfaceContainerLow = PaperTonalSoft,
    surfaceContainer = PaperTonal,
    surfaceContainerHigh = Color(0xFFE3E6EC),
    outline = Color(0xFFC5CAD4),
    outlineVariant = HairlineLight,
    error = VermilionStopLight,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = GlacierIndigoDark,
    onPrimary = Color(0xFF081432),
    primaryContainer = GlacierContainerDark,
    onPrimaryContainer = Color(0xFFD0DEFF),
    secondary = JadeReadyDark,
    onSecondary = Color(0xFF04261C),
    tertiary = AmberNoticeDark,
    onTertiary = Color(0xFF281900),
    background = ObsidianGround,
    onBackground = MistInk,
    surface = ObsidianSheet,
    onSurface = MistInk,
    surfaceVariant = ObsidianTonal,
    onSurfaceVariant = AshMuted,
    surfaceContainerLow = ObsidianTonalSoft,
    surfaceContainer = ObsidianTonal,
    surfaceContainerHigh = Color(0xFF232B38),
    outline = Color(0xFF475061),
    outlineVariant = HairlineDark,
    error = VermilionStopDark,
    onError = Color(0xFF330A0A),
)

private val AmoledColors = DarkColors.copy(
    background = OledGround,
    surface = OledSheet,
    surfaceVariant = OledTonal,
    surfaceContainerLow = Color(0xFF0E1219),
    surfaceContainer = Color(0xFF151A23),
    surfaceContainerHigh = Color(0xFF1D2330),
    outlineVariant = HairlineOled,
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
