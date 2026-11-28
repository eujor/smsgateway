package com.eujor.smsgateway.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val GatewayLightColors = lightColorScheme(
    primary = Color(0xFF145B63),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F0F0),
    onPrimaryContainer = Color(0xFF123A40),
    secondary = Color(0xFF356C8A),
    tertiary = Color(0xFFB66A36),
    background = Color(0xFFF5F7F8),
    surface = Color.White,
    surfaceVariant = Color(0xFFEAF0F2),
    error = Color(0xFFB3261E)
)

private val GatewayDarkColors = darkColorScheme(
    primary = Color(0xFF84C9C8),
    onPrimary = Color(0xFF07383D),
    primaryContainer = Color(0xFF1C454B),
    onPrimaryContainer = Color(0xFFD9F2F0),
    secondary = Color(0xFF9CC4D8),
    tertiary = Color(0xFFE5B184),
    background = Color(0xFF111B23),
    surface = Color(0xFF192630),
    surfaceVariant = Color(0xFF253641),
    error = Color(0xFFFFB4AB)
)

private val GatewayShapes = Shapes(
    extraSmall = RoundedCornerShape(5.dp),
    small = RoundedCornerShape(9.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun GatewayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) GatewayDarkColors else GatewayLightColors,
        shapes = GatewayShapes,
        content = content
    )
}
