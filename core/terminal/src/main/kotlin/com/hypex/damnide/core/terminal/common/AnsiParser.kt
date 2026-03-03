package com.hypex.damnide.core.terminal.common

import androidx.compose.ui.graphics.Color
import com.hypex.damnide.core.terminal.model.StyledSpan
import com.hypex.damnide.core.terminal.model.TerminalLine
import com.hypex.damnide.core.terminal.model.TerminalStyle

class AnsiParser(
    private val maxLines: Int = 2_000,
) {
    private val lines = ArrayDeque<MutableList<StyledSpan>>()
    private var currentLine = mutableListOf<StyledSpan>()
    private var activeStyle = TerminalStyle()
    private val buffer = StringBuilder()

    init {
        lines.addLast(currentLine)
    }

    fun append(chunk: String): List<TerminalLine> {
        buffer.append(chunk)
        parseBuffer()
        return lines.map { TerminalLine(it.toList()) }
    }

    private fun parseBuffer() {
        var i = 0
        while (i < buffer.length) {
            when (val ch = buffer[i]) {
                '\u001B' -> {
                    val consumed = consumeEscape(i)
                    if (consumed <= 0) break
                    i += consumed
                }
                '\n' -> {
                    newLine()
                    i++
                }
                '\r' -> {
                    currentLine.clear()
                    i++
                }
                '\b' -> {
                    backspace()
                    i++
                }
                else -> {
                    appendText(ch.toString())
                    i++
                }
            }
        }
        if (i > 0) {
            buffer.delete(0, i)
        }
    }

    private fun consumeEscape(index: Int): Int {
        if (index + 1 >= buffer.length || buffer[index + 1] != '[') return 1
        var cursor = index + 2
        while (cursor < buffer.length && !buffer[cursor].isLetter()) cursor++
        if (cursor >= buffer.length) return 0

        val command = buffer[cursor]
        val args = buffer.substring(index + 2, cursor)
        if (command == 'm') {
            applySgr(args)
        }
        return cursor - index + 1
    }

    private fun applySgr(argString: String) {
        val codes = if (argString.isBlank()) listOf(0) else argString.split(';').mapNotNull { it.toIntOrNull() }
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> activeStyle = TerminalStyle()
                1 -> activeStyle = activeStyle.copy(bold = true)
                22 -> activeStyle = activeStyle.copy(bold = false)
                in 30..37 -> activeStyle = activeStyle.copy(foreground = baseColor(code - 30))
                39 -> activeStyle = activeStyle.copy(foreground = TerminalStyle().foreground)
                in 40..47 -> activeStyle = activeStyle.copy(background = baseColor(code - 40))
                49 -> activeStyle = activeStyle.copy(background = Color.Transparent)
                38 -> {
                    if (i + 2 < codes.size && codes[i + 1] == 5) {
                        activeStyle = activeStyle.copy(foreground = color256(codes[i + 2]))
                        i += 2
                    }
                }
                48 -> {
                    if (i + 2 < codes.size && codes[i + 1] == 5) {
                        activeStyle = activeStyle.copy(background = color256(codes[i + 2]))
                        i += 2
                    }
                }
            }
            i++
        }
    }

    private fun appendText(text: String) {
        if (currentLine.isNotEmpty() && currentLine.last().style == activeStyle) {
            val last = currentLine.removeLast()
            currentLine.add(last.copy(text = last.text + text))
        } else {
            currentLine.add(StyledSpan(text = text, style = activeStyle))
        }
    }

    private fun backspace() {
        if (currentLine.isEmpty()) return
        val last = currentLine.removeLast()
        if (last.text.length > 1) {
            currentLine.add(last.copy(text = last.text.dropLast(1)))
        }
    }

    private fun newLine() {
        if (lines.size >= maxLines) lines.removeFirst()
        currentLine = mutableListOf()
        lines.addLast(currentLine)
    }

    private fun baseColor(index: Int): Color {
        val colors = listOf(
            Color(0xFF000000),
            Color(0xFFCD3131),
            Color(0xFF0DBC79),
            Color(0xFFE5E510),
            Color(0xFF2472C8),
            Color(0xFFBC3FBC),
            Color(0xFF11A8CD),
            Color(0xFFE5E5E5),
        )
        return colors.getOrElse(index) { TerminalStyle().foreground }
    }

    private fun color256(code: Int): Color {
        if (code < 0) return TerminalStyle().foreground
        if (code < 16) {
            return when (code) {
                0 -> Color(0xFF000000)
                1 -> Color(0xFF800000)
                2 -> Color(0xFF008000)
                3 -> Color(0xFF808000)
                4 -> Color(0xFF000080)
                5 -> Color(0xFF800080)
                6 -> Color(0xFF008080)
                7 -> Color(0xFFC0C0C0)
                8 -> Color(0xFF808080)
                9 -> Color(0xFFFF0000)
                10 -> Color(0xFF00FF00)
                11 -> Color(0xFFFFFF00)
                12 -> Color(0xFF0000FF)
                13 -> Color(0xFFFF00FF)
                14 -> Color(0xFF00FFFF)
                else -> Color(0xFFFFFFFF)
            }
        }
        if (code in 16..231) {
            val offset = code - 16
            val r = offset / 36
            val g = (offset % 36) / 6
            val b = offset % 6
            fun channel(v: Int): Int = if (v == 0) 0 else 55 + v * 40
            return Color(channel(r), channel(g), channel(b))
        }
        if (code in 232..255) {
            val gray = 8 + (code - 232) * 10
            return Color(gray, gray, gray)
        }
        return TerminalStyle().foreground
    }
}
