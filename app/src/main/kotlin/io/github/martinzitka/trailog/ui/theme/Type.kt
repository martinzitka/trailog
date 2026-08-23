package io.github.martinzitka.trailog.ui.theme

import androidx.compose.material3.Typography

/**
 * Typography for Trailog. The Material 3 defaults are used verbatim for now — the system font
 * only, since no third-party or externally hosted fonts are permitted (CLAUDE.md: no external
 * fonts). A named holder exists so type scales can be tuned in one place later without touching
 * the theme wiring.
 */
val TrailogTypography: Typography = Typography()
