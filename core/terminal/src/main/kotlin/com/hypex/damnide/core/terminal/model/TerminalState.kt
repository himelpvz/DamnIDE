package com.hypex.damnide.core.terminal.model

import android.net.Uri
import androidx.compose.ui.graphics.Color

data class TerminalStyle(
    val foreground: Color = Color(0xFFD4D4D4),
    val background: Color = Color.Transparent,
    val bold: Boolean = false,
)

data class StyledSpan(
    val text: String,
    val style: TerminalStyle,
)

data class TerminalLine(
    val spans: List<StyledSpan>,
)

data class TerminalState(
    val sessionName: String = "local-shell",
    val selectedRootfsUri: Uri? = null,
    val selectedRootfsPath: String? = null,
    val isRunning: Boolean = false,
    val lines: List<TerminalLine> = emptyList(),
    val cursorVisible: Boolean = true,
    val errorMessage: String? = null,
)
