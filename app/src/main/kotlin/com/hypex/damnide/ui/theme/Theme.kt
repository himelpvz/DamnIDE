package com.hypex.damnide.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TerminalColorScheme = darkColorScheme(
    primary = TerminalForeground,
    onPrimary = TerminalBackground,
    background = TerminalBackground,
    surface = TerminalSurface,
    onSurface = TerminalForeground,
)

@Composable
fun ComposeEmptyActivityTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TerminalColorScheme,
        typography = Typography,
        content = content,
    )
}
