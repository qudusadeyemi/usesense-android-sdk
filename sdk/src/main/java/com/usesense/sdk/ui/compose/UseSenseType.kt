package com.usesense.sdk.ui.compose

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
