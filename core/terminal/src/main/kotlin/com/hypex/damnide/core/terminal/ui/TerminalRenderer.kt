package com.hypex.damnide.core.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import com.hypex.damnide.core.terminal.model.TerminalLine

@Composable
fun TerminalRenderer(
    lines: List<TerminalLine>,
    cursorVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.wrapContentHeight()) {
        lines.forEachIndexed { index, line ->
            val annotated = buildAnnotatedString {
                line.spans.forEach { span ->
                    withStyle(
                        SpanStyle(
                            color = span.style.foreground,
                            background = span.style.background,
                            fontWeight = if (span.style.bold) FontWeight.Bold else FontWeight.Normal,
                        ),
                    ) {
                        append(span.text)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = annotated,
                    color = Color(0xFFD4D4D4),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                if (cursorVisible && index == lines.lastIndex) {
                    Box(
                        modifier = Modifier
                            .size(width = 8.dp, height = 16.dp)
                            .background(Color(0xFFA6E3A1)),
                    )
                }
            }
        }
    }
}
