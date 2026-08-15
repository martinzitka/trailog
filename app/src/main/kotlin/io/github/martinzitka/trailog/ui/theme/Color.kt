package io.github.martinzitka.trailog.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Trailog's brand palette — the fallback colour scheme used on Android 11 and below, where
 * Material You dynamic colour is unavailable. A trail-green primary with earthy secondaries,
 * legible against both light and dark surfaces.
 *
 * These are the only literal colours in the app. Everything downstream reads
 * [androidx.compose.material3.MaterialTheme.colorScheme] tokens (CLAUDE.md's "Definition of
 * done": no hardcoded colours). Do not reference these constants from a screen.
 */

// --- Light scheme ---
internal val TrailGreen = Color(0xFF2E6B3E)
internal val TrailGreenOn = Color(0xFFFFFFFF)
internal val TrailGreenContainer = Color(0xFFB0F1BC)
internal val TrailGreenOnContainer = Color(0xFF00210C)

internal val ClayOrange = Color(0xFF8F4E2A)
internal val ClayOrangeOn = Color(0xFFFFFFFF)
internal val ClayOrangeContainer = Color(0xFFFFDBC9)
internal val ClayOrangeOnContainer = Color(0xFF351000)

internal val SkyBlue = Color(0xFF3B6178)
internal val SkyBlueOn = Color(0xFFFFFFFF)
internal val SkyBlueContainer = Color(0xFFBFE9FF)
internal val SkyBlueOnContainer = Color(0xFF001E2C)

internal val ErrorRed = Color(0xFFBA1A1A)
internal val ErrorRedOn = Color(0xFFFFFFFF)
internal val ErrorRedContainer = Color(0xFFFFDAD6)
internal val ErrorRedOnContainer = Color(0xFF410002)

internal val LightBackground = Color(0xFFF6FBF3)
internal val LightOnBackground = Color(0xFF181D18)
internal val LightSurfaceVariant = Color(0xFFDCE5DA)
internal val LightOnSurfaceVariant = Color(0xFF414941)
internal val LightOutline = Color(0xFF717970)

// --- Dark scheme ---
internal val TrailGreenDark = Color(0xFF95D5A2)
internal val TrailGreenOnDark = Color(0xFF00391A)
internal val TrailGreenContainerDark = Color(0xFF135228)
internal val TrailGreenOnContainerDark = Color(0xFFB0F1BC)

internal val ClayOrangeDark = Color(0xFFFFB596)
internal val ClayOrangeOnDark = Color(0xFF552100)
internal val ClayOrangeContainerDark = Color(0xFF723514)
internal val ClayOrangeOnContainerDark = Color(0xFFFFDBC9)

internal val SkyBlueDark = Color(0xFFA6CCE4)
internal val SkyBlueOnDark = Color(0xFF083447)
internal val SkyBlueContainerDark = Color(0xFF224B5F)
internal val SkyBlueOnContainerDark = Color(0xFFBFE9FF)

internal val ErrorRedDark = Color(0xFFFFB4AB)
internal val ErrorRedOnDark = Color(0xFF690005)
internal val ErrorRedContainerDark = Color(0xFF93000A)
internal val ErrorRedOnContainerDark = Color(0xFFFFDAD6)

internal val DarkBackground = Color(0xFF101510)
internal val DarkOnBackground = Color(0xFFE0E4DC)
internal val DarkSurfaceVariant = Color(0xFF414941)
internal val DarkOnSurfaceVariant = Color(0xFFC0C9BD)
internal val DarkOutline = Color(0xFF8B938A)
