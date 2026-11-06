/* Adapted from Flocket's Material theme and shape scale.
 * Copyright Flocket contributors. Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package com.tajchert.trefoil.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TrefoilTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFA4D6AA), onPrimary = Color(0xFF10391F),
        primaryContainer = Color(0xFF285134), onPrimaryContainer = Color(0xFFC0F2C5),
        background = Color(0xFF101611), surface = Color(0xFF101611),
        surfaceContainer = Color(0xFF1C231D), surfaceContainerLow = Color(0xFF182019),
    ) else lightColorScheme(
        primary = Color(0xFF326B41), onPrimary = Color.White,
        primaryContainer = Color(0xFFC7EDCB), onPrimaryContainer = Color(0xFF12381C),
        background = Color(0xFFF7FAF3), surface = Color(0xFFF7FAF3),
        surfaceContainer = Color(0xFFEBF1E7), surfaceContainerLow = Color(0xFFF0F5EC),
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp)), content = content)
}
