package com.eujor.smsgateway.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun GatewayTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF72D6C5),
            secondary = Color(0xFFFFC078),
            tertiary = Color(0xFF9FB7FF),
            background = Color(0xFF101827),
            surface = Color(0xFF172235)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF14213D),
            secondary = Color(0xFF0F9D8A),
            tertiary = Color(0xFFF4A261),
            background = Color(0xFFF2F5FA),
            surface = Color.White,
            surfaceVariant = Color(0xFFE7EDF5)
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
