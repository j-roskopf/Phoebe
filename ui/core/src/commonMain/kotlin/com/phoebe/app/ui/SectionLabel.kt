package com.phoebe.app.ui

import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

@Composable
fun SectionLabel(
    label: String,
    color: Color,
    fontSize: TextUnit = 11.sp,
    fontWeight: FontWeight = FontWeight.Bold,
) {
    val letterSpacing = if (PhoebeUi.design == PhoebeDesignSystem.Brutalist) 0.12.em else 0.08.em
    Text(
        label.uppercase(),
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = MaterialTheme.typography.labelSmall.fontFamily,
        letterSpacing = letterSpacing,
    )
}
