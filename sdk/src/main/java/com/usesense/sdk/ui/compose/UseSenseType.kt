package com.usesense.sdk.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.usesense.sdk.R

// Bundled brand fonts (OFL): Outfit (display), DM Sans (body), JetBrains Mono.
val OutfitFamily = FontFamily(
    Font(R.font.outfit_bold, FontWeight.Bold),
    Font(R.font.outfit_extrabold, FontWeight.ExtraBold),
)
val DMSansFamily = FontFamily(
    Font(R.font.dm_sans_regular, FontWeight.Normal),
    Font(R.font.dm_sans_medium, FontWeight.Medium),
    Font(R.font.dm_sans_semibold, FontWeight.SemiBold),
)
val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

/** Type ramp mirroring hosted theme.css (16sp base). */
object USType {
    val h1 = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, letterSpacing = (-0.05).em, lineHeight = 36.sp)
    val h2 = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Bold, fontSize = 23.sp, letterSpacing = (-0.03).em, lineHeight = 27.sp)
    val h3 = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = (-0.02).em, lineHeight = 22.sp)
    val h4 = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    val body = TextStyle(fontFamily = DMSansFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val bodyMedium = TextStyle(fontFamily = DMSansFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp)
    val label = TextStyle(fontFamily = DMSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.06.em)
    val button = TextStyle(fontFamily = DMSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    val mono = TextStyle(fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp)
}

/**
 * Resolved type accessor. When a white-label appearance supplies custom font
 * families (via [LocalUseSenseFonts]), this remaps the bundled brand families:
 * Outfit (display) -> the appearance's display font, DM Sans (body) -> the body
 * font. Styles using the mono family are left untouched. Brand components and the
 * parity screens read these so a supplied typography propagates automatically;
 * with no appearance fonts set they return the unchanged bundled [USType] styles.
 */
object UseSenseTypeRamp {
    @Composable @ReadOnlyComposable
    private fun TextStyle.themed(): TextStyle {
        val fonts = LocalUseSenseFonts.current
        return when (fontFamily) {
            OutfitFamily -> fonts.display?.let { copy(fontFamily = it) } ?: this
            DMSansFamily -> fonts.body?.let { copy(fontFamily = it) } ?: this
            else -> this
        }
    }

    val h1: TextStyle @Composable @ReadOnlyComposable get() = USType.h1.themed()
    val h2: TextStyle @Composable @ReadOnlyComposable get() = USType.h2.themed()
    val h3: TextStyle @Composable @ReadOnlyComposable get() = USType.h3.themed()
    val h4: TextStyle @Composable @ReadOnlyComposable get() = USType.h4.themed()
    val body: TextStyle @Composable @ReadOnlyComposable get() = USType.body.themed()
    val bodyMedium: TextStyle @Composable @ReadOnlyComposable get() = USType.bodyMedium.themed()
    val label: TextStyle @Composable @ReadOnlyComposable get() = USType.label.themed()
    val button: TextStyle @Composable @ReadOnlyComposable get() = USType.button.themed()
    val mono: TextStyle @Composable @ReadOnlyComposable get() = USType.mono.themed()
}
