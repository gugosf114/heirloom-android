package com.heirloom.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.heirloom.app.R

val Editorial = FontFamily(Font(R.font.cormorant))
val StudioSans = FontFamily(Font(R.font.manrope))
private fun editorial(size: Int, height: Int) = TextStyle(
    fontFamily = Editorial, fontWeight = FontWeight.Normal,
    fontSize = size.sp, lineHeight = height.sp, letterSpacing = (-0.6).sp,
)
private fun sans(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = StudioSans, fontWeight = weight, fontSize = size.sp, lineHeight = height.sp,
)
val HeirloomTypography = Typography(
    displayLarge = editorial(52, 52),
    displayMedium = editorial(44, 46),
    displaySmall = editorial(38, 40),
    headlineLarge = editorial(36, 40),
    headlineMedium = editorial(30, 34),
    headlineSmall = editorial(26, 30),
    titleLarge = editorial(28, 32),
    titleMedium = sans(16, 23, FontWeight.SemiBold),
    titleSmall = sans(14, 20, FontWeight.SemiBold),
    bodyLarge = sans(16, 25),
    bodyMedium = sans(14, 22),
    bodySmall = sans(12, 19),
    labelLarge = sans(15, 22, FontWeight.SemiBold),
    labelMedium = sans(12, 18, FontWeight.Medium),
    labelSmall = sans(11, 17, FontWeight.Medium).copy(letterSpacing = 1.4.sp),
)
