package io.github.ddastoor.kindlify.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp

/**
 * Shows formatted (fetched) text styled on the canvas: "## " headings big and bold,
 * "> " quotes in italics, **bold** and *italic* inline, with the markup characters dimmed.
 * The text itself is unchanged (identity offset mapping), so editing works as plain text.
 * Same markup rules as the EPUB builder.
 */
class MarkupHighlighter(private val dim: Color, private val quote: Color) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val out = AnnotatedString.Builder(s)
        var lineStart = 0
        for (line in s.split('\n')) {
            val lineEnd = lineStart + line.length
            val indent = line.length - line.trimStart().length
            val trimmed = line.trimStart()
            val heading = HEADING.find(trimmed)
            when {
                heading != null -> {
                    val big = heading.groupValues[1].length < 3
                    out.addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = if (big) 21.sp else 18.sp), lineStart, lineEnd)
                    out.addStyle(SpanStyle(color = dim), lineStart, lineStart + indent + heading.value.length)
                }
                trimmed.startsWith(">") -> {
                    out.addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = quote), lineStart, lineEnd)
                    out.addStyle(SpanStyle(color = dim), lineStart + indent, lineStart + indent + 1)
                }
            }
            styleInline(line, lineStart, out)
            lineStart = lineEnd + 1
        }
        return TransformedText(out.toAnnotatedString(), OffsetMapping.Identity)
    }

    private fun styleInline(line: String, offset: Int, out: AnnotatedString.Builder) {
        // Hide escaped characters from the matchers without shifting positions.
        var work = line.replace("\\\\", "\u0000\u0000").replace("\\*", "\u0000\u0000")
        for (m in BOLD.findAll(work)) {
            out.addStyle(SpanStyle(fontWeight = FontWeight.Bold), offset + m.range.first, offset + m.range.last + 1)
            out.addStyle(SpanStyle(color = dim), offset + m.range.first, offset + m.range.first + 2)
            out.addStyle(SpanStyle(color = dim), offset + m.range.last - 1, offset + m.range.last + 1)
            // So the single-star matcher below doesn't pick these markers up again.
            work = work.replaceRange(m.range.first, m.range.first + 2, "\u0000\u0000")
                .replaceRange(m.range.last - 1, m.range.last + 1, "\u0000\u0000")
        }
        for (m in ITALIC.findAll(work)) {
            out.addStyle(SpanStyle(fontStyle = FontStyle.Italic), offset + m.range.first, offset + m.range.last + 1)
            out.addStyle(SpanStyle(color = dim), offset + m.range.first, offset + m.range.first + 1)
            out.addStyle(SpanStyle(color = dim), offset + m.range.last, offset + m.range.last + 1)
        }
    }

    // Same rules as EpubBuilder.markupToXhtml.
    private companion object {
        val HEADING = Regex("^(#{1,6})\\s+")
        val BOLD = Regex("\\*\\*(\\S(?:.*?\\S)?)\\*\\*")
        val ITALIC = Regex("\\*([^\\s\\u0000](?:.*?[^\\s\\u0000])?)\\*")
    }
}
