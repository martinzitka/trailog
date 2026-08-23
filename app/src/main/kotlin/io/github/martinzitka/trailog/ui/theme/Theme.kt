package io.github.martinzitka.trailog.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The app-wide Material 3 theme. Prefers Material You dynamic colour on Android 12+ so Trailog
 * blends with the user's device palette; falls back to the trail-green brand scheme in
 * [Color.kt] on older devices. Both light and dark are supported and follow the system setting
 * (CLAUDE.md's per-screen "Definition of done": renders correctly in light and dark).
 *
 * @param dynamicColor whether to use Material You dynamic colour where available. Defaults on;
 *   a future Settings toggle can force the brand palette by passing false.
 */
@Composable
fun TrailogTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = TrailogTypography,
        content = content,
    )
}

private val LightColors: ColorScheme = lightColorScheme(
    primary = TrailGreen,
    onPrimary = TrailGreenOn,
    primaryContainer = TrailGreenContainer,
    onPrimaryContainer = TrailGreenOnContainer,
    secondary = ClayOrange,
    onSecondary = ClayOrangeOn,
    secondaryContainer = ClayOrangeContainer,
    onSecondaryContainer = ClayOrangeOnContainer,
    tertiary = SkyBlue,
    onTertiary = SkyBlueOn,
    tertiaryContainer = SkyBlueContainer,
    onTertiaryContainer = SkyBlueOnContainer,
    error = ErrorRed,
    onError = ErrorRedOn,
    errorContainer = ErrorRedContainer,
    onErrorContainer = ErrorRedOnContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightBackground,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = TrailGreenDark,
    onPrimary = TrailGreenOnDark,
    primaryContainer = TrailGreenContainerDark,
    onPrimaryContainer = TrailGreenOnContainerDark,
    secondary = ClayOrangeDark,
    onSecondary = ClayOrangeOnDark,
    secondaryContainer = ClayOrangeContainerDark,
    onSecondaryContainer = ClayOrangeOnContainerDark,
    tertiary = SkyBlueDark,
    onTertiary = SkyBlueOnDark,
    tertiaryContainer = SkyBlueContainerDark,
    onTertiaryContainer = SkyBlueOnContainerDark,
    error = ErrorRedDark,
    onError = ErrorRedOnDark,
    errorContainer = ErrorRedContainerDark,
    onErrorContainer = ErrorRedOnContainerDark,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkBackground,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
)
