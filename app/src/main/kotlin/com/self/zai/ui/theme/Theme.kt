package com.self.zai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.self.zai.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = TerracottaLight,
    onPrimary = Color(0xFFFFFCF9),
    primaryContainer = TerracottaContainerLight,
    onPrimaryContainer = TerracottaOnContainerLight,
    secondary = SageReadyLight,
    onSecondary = Color.White,
    tertiary = OchreNoticeLight,
    onTertiary = Color.White,
    background = AlabasterGround,
    onBackground = CharcoalInk,
    surface = ParchmentSheet,
    onSurface = CharcoalInk,
    surfaceVariant = LinenTonal,
    onSurfaceVariant = WarmTaupe,
    surfaceContainerLow = LinenTonalSoft,
    surfaceContainer = LinenTonal,
    surfaceContainerHigh = LinenTonalHigh,
    outline = StoneOutline,
    outlineVariant = StoneHairline,
    error = RustStopLight,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = TerracottaDark,
    onPrimary = Color(0xFF23110A),
    primaryContainer = TerracottaContainerDark,
    onPrimaryContainer = TerracottaOnContainerDark,
    secondary = SageReadyDark,
    onSecondary = Color(0xFF0A2417),
    tertiary = OchreNoticeDark,
    onTertiary = Color(0xFF291A04),
    background = EspressoGround,
    onBackground = IvoryInk,
    surface = EspressoSheet,
    onSurface = IvoryInk,
    surfaceVariant = EspressoTonal,
    onSurfaceVariant = AshTaupe,
    surfaceContainerLow = EspressoTonalSoft,
    surfaceContainer = EspressoTonal,
    surfaceContainerHigh = EspressoTonalHigh,
    outline = EspressoOutline,
    outlineVariant = EspressoHairline,
    error = RustStopDark,
    onError = Color(0xFF330D09),
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
