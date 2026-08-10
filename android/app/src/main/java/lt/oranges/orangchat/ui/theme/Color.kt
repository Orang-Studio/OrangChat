package lt.oranges.orangchat.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * OrangChat design tokens, ported 1:1 from packages/client/src/styles/index.css.
 * The CSS carries these as --oc-* custom properties that flip on [data-theme];
 * here they live as two [OrangColors] instances (dark default + light) exposed
 * through a CompositionLocal so any composable can read theme-aware surfaces.
 */
data class OrangColors(
    // Surfaces, deepest (server rail) -> highest (popovers)
    val surface0: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val surface4: Color,
    val border: Color,
    val borderStrong: Color,
    /** Plate under a message group when the conversation has a background
     *  picture (--oc-plate). Opaque enough that even muted text keeps roughly
     *  the contrast it has on a plain surface; the picture reads in the gutters
     *  and in the gaps between groups instead. */
    val plate: Color,
    // Text
    val ink: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val inkOnPrimary: Color,
    // Brand
    val primary: Color,
    val primaryHover: Color,
    val primaryActive: Color,
    val primarySoft: Color,
    // Status
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val isDark: Boolean,
)

// Dark - the default. Cool graphite base, vivid tangerine accent.
val DarkOrangColors = OrangColors(
    surface0 = Color(0xFF08090C),
    surface1 = Color(0xFF0E0F13),
    surface2 = Color(0xFF14151B),
    surface3 = Color(0xFF1C1E26),
    surface4 = Color(0xFF24262F),
    border = Color(0xFF21232C),
    borderStrong = Color(0xFF353845),
    plate = Color(0xFF0E0F13).copy(alpha = 0.88f),
    ink = Color(0xFFF3F4F7),
    inkSecondary = Color(0xFFB0B3BF),
    inkMuted = Color(0xFF71747F),
    inkOnPrimary = Color(0xFF1C0E02),
    primary = Color(0xFFFF6A1A),
    primaryHover = Color(0xFFFF8340),
    primaryActive = Color(0xFFEE5D0E),
    primarySoft = Color(0xFFFF6A1A).copy(alpha = 0.16f),
    success = Color(0xFF3FBD6E),
    warning = Color(0xFFE2AB35),
    danger = Color(0xFFF0554C),
    info = Color(0xFF4F9FF2),
    isDark = true,
)

val LightOrangColors = OrangColors(
    surface0 = Color(0xFFE6E7EC),
    surface1 = Color(0xFFEEF0F4),
    surface2 = Color(0xFFF5F6F9),
    surface3 = Color(0xFFFBFBFD),
    surface4 = Color(0xFFFFFFFF),
    border = Color(0xFFE2E4EA),
    borderStrong = Color(0xFFC7CAD4),
    plate = Color(0xFFEEF0F4).copy(alpha = 0.88f),
    ink = Color(0xFF14161C),
    inkSecondary = Color(0xFF51535F),
    inkMuted = Color(0xFF82848F),
    inkOnPrimary = Color(0xFFFFFFFF),
    primary = Color(0xFFE85D04),
    primaryHover = Color(0xFFF56C12),
    primaryActive = Color(0xFFCF5203),
    primarySoft = Color(0xFFE85D04).copy(alpha = 0.11f),
    success = Color(0xFF2E9E4F),
    warning = Color(0xFFB3821D),
    danger = Color(0xFFD13B32),
    info = Color(0xFF2F7FC2),
    isDark = false,
)

val LocalOrangColors = staticCompositionLocalOf { DarkOrangColors }

/** Ergonomic accessor: `OrangTheme.colors.primary` from any composable. */
object OrangTheme {
    val colors: OrangColors
        @Composable
        @ReadOnlyComposable
        get() = LocalOrangColors.current
}
