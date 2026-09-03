package com.homoludens.citacknjiga.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val CitacLightColors = lightColorScheme(
    primary = Color(0xFF6D3BC1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDDFF),
    onPrimaryContainer = Color(0xFF2B0053),
    secondary = Color(0xFF665A70),
    secondaryContainer = Color(0xFFEEE0F7),
    onSecondaryContainer = Color(0xFF211829),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1D1A20),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE9E0EC),
    outline = Color(0xFF7B747E),
    outlineVariant = Color(0xFFCDC4CF),
)

private val CitacShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
public fun CitacKnjigaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CitacLightColors,
        shapes = CitacShapes,
        content = content,
    )
}
