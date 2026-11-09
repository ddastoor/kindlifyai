package io.github.ddastoor.kindlify.epub

import io.documentnode.epub4j.domain.Book
import io.documentnode.epub4j.domain.GuideReference
import io.documentnode.epub4j.domain.Identifier
import io.documentnode.epub4j.domain.Resource
import io.documentnode.epub4j.epub.EpubWriter
import java.io.File
import java.io.OutputStream
import java.util.UUID

/** One piece of canvas content, in reading order. */
sealed interface EpubBlock {
    /**
     * Text. Plain text ([formatted] = false) goes in exactly as written: blank lines
     * separate paragraphs, single newlines are kept. [formatted] text uses a small markup
     * (see [EpubBuilder.markupToXhtml]) for headings, quotes, lists, bold and italic.
     */
    data class Text(val text: String, val formatted: Boolean = false) : EpubBlock

    /** A JPEG, PNG or GIF file. */
    data class Image(val file: File) : EpubBlock

    /** Starts a new chapter. [title] names it in the table of contents; null = named after its first line. */
    data class ChapterBreak(val title: String? = null) : EpubBlock
}

/**
 * Builds an EPUB from canvas content.
 *
 * epub4j (a maintained fork of epublib) does the EPUB work: the zip layout,
 * the `mimetype` entry, container.xml, the OPF package document and the NCX
 * table of contents. This class turns the canvas into XHTML pages (one per
 * chapter) and hands them, a stylesheet and the images to epub4j.
 *
 * Without chapter breaks the book is a single page, `content.xhtml`, headed by the book title.
 * Each [EpubBlock.ChapterBreak] starts a new page and a new table-of-contents entry, which
 * the Kindle lists in its "Go to" menu.
 */
object EpubBuilder {

    fun write(
        title: String,
        blocks: List<EpubBlock>,
        out: OutputStream,
        language: String = "en",
        bookId: String = UUID.randomUUID().toString(),
    ) {
        val cleanTitle = sanitize(title).trim().ifEmpty { "Untitled" }
        val book = Book()
        book.metadata.addTitle(cleanTitle)
        book.metadata.language = language
        book.metadata.addIdentifier(Identifier("UUID", bookId))

        book.addResource(Resource(STYLESHEET.toByteArray(Charsets.UTF_8), "style.css"))

        var imageCount = 0
        var firstPage: Resource? = null
        chapters(blocks, cleanTitle).forEachIndexed { index, chapter ->
            val body = StringBuilder()
            for (block in chapter.blocks) {
                when (block) {
                    is EpubBlock.Text -> body.append(if (block.formatted) markupToXhtml(block.text) else textToXhtml(block.text))
                    is EpubBlock.Image -> {
                        val bytes = block.file.readBytes()
                        imageCount++
                        val href = "images/image" + imageCount.toString().padStart(3, '0') + "." + imageExtension(bytes)
                        book.addResource(Resource(bytes, href))
                        body.append("<div class=\"image\"><img src=\"").append(href).append("\" alt=\"\"/></div>\n")
                    }
                    is EpubBlock.ChapterBreak -> Unit // handled by chapters()
                }
            }
            val fileName = if (index == 0) "content.xhtml" else "chapter-" + (index + 1).toString().padStart(3, '0') + ".xhtml"
            val page = Resource(xhtmlPage(chapter.title, language, body.toString()).toByteArray(Charsets.UTF_8), fileName)
            book.addSection(chapter.title, page)
            if (firstPage == null) firstPage = page
        }

        // EPUB 2 requires at least one guide entry; this one also tells Kindle where reading starts.
        book.guide.addReference(GuideReference(firstPage, GuideReference.TEXT, cleanTitle))
        EpubWriter().write(book, out)
    }

    private class Chapter(val title: String, val blocks: List<EpubBlock>)

    /**
     * Splits the content at chapter breaks. The part before the first break is headed by the
     * book title; empty parts (e.g. a break at the very start) are left out.
     */
    private fun chapters(blocks: List<EpubBlock>, bookTitle: String): List<Chapter> {
        val parts = mutableListOf<Pair<String?, MutableList<EpubBlock>>>(null to mutableListOf())
        for (block in blocks) {
            if (block is EpubBlock.ChapterBreak) parts += block.title to mutableListOf() else parts.last().second += block
        }
        val chapters = parts.withIndex()
            .filter { (_, part) -> part.second.any(::hasContent) }
            .map { (index, part) ->
                val (breakTitle, content) = part
                val title = when {
                    index == 0 -> bookTitle
                    !breakTitle.isNullOrBlank() -> sanitize(breakTitle).trim()
                    else -> titleFromFirstLine(content) ?: "Part ${index + 1}"
                }
                Chapter(title, content)
            }
        return chapters.ifEmpty { listOf(Chapter(bookTitle, emptyList())) }
    }

    private fun hasContent(block: EpubBlock) = when (block) {
        is EpubBlock.Text -> sanitize(block.text).isNotBlank()
        is EpubBlock.Image -> true
        is EpubBlock.ChapterBreak -> false
    }

    /** A chapter without a title is named after its first line of text (markup removed, at most ~60 characters). */
    private fun titleFromFirstLine(blocks: List<EpubBlock>): String? {
        val text = blocks.firstOrNull { it is EpubBlock.Text && sanitize(it.text).isNotBlank() } as? EpubBlock.Text ?: return null
        var line = sanitize(text.text).lineSequence().map { it.trim() }.first { it.isNotEmpty() }
        if (text.formatted) {
            line = line.replace(Regex("^(#{1,6}\\s+|>\\s*|•\\s+|\\d+\\.\\s+)"), "")
                .replace("\\\\", "\uE000").replace("\\*", "\uE001").replace("*", "")
                .replace("\uE000", "\\").replace("\uE001", "*")
                .trim()
        }
        if (line.isEmpty()) return null
        if (line.length <= 60) return line
        val cut = line.take(60)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > 30 } ?: 60).trimEnd() + "…"
    }

    /** Plain text to XHTML: blank lines separate paragraphs, single newlines become line breaks. */
    fun textToXhtml(raw: String): String {
        val text = sanitize(raw).replace("\r\n", "\n").replace('\r', '\n')
        return text.split(PARAGRAPH_BREAK)
            .map { it.trim('\n') }
            .filter { it.isNotBlank() }
            .joinToString("") { paragraph ->
                "<p>" + paragraph.lines().joinToString("<br/>\n") { escape(it) } + "</p>\n"
            }
    }

    /**
     * Formatted text to XHTML. Paragraphs are separated by blank lines, and a paragraph
     * starting with "## " or "# " is a heading (h2), "### " a smaller one (h3), "> " a quote,
     * "• " a bullet-list item and "1. " a numbered-list item; consecutive quotes and list
     * items are grouped. Inline, **bold** and *italic*; a backslash keeps a literal \* or \\.
     */
    fun markupToXhtml(raw: String): String {
        val text = sanitize(raw).replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = text.split(PARAGRAPH_BREAK).map { it.trim('\n') }.filter { it.isNotBlank() }
        val out = StringBuilder()
        var openList: String? = null
        var inQuote = false

        fun closeList() {
            openList?.let { out.append("</").append(it).append(">\n") }
            openList = null
        }
        fun closeQuote() {
            if (inQuote) out.append("</blockquote>\n")
            inQuote = false
        }
        fun lines(paragraph: String, strip: (String) -> String = { it }) =
            paragraph.lines().joinToString("<br/>\n") { inline(strip(it)) }

        for (paragraph in paragraphs) {
            val firstLine = paragraph.lineSequence().first().trimStart()
            val rest = paragraph.lines().drop(1).joinToString("\n").trim('\n')
            val heading = HEADING.find(firstLine)
            when {
                heading != null -> {
                    closeList(); closeQuote()
                    val tag = if (heading.groupValues[1].length >= 3) "h3" else "h2"
                    out.append("<").append(tag).append(">").append(inline(firstLine.substring(heading.value.length).trim()))
                        .append("</").append(tag).append(">\n")
                    if (rest.isNotBlank()) out.append("<p>").append(lines(rest)).append("</p>\n")
                }
                firstLine.startsWith(">") -> {
                    closeList()
                    if (!inQuote) out.append("<blockquote>\n")
                    inQuote = true
                    out.append("<p>").append(lines(paragraph) { it.trimStart().removePrefix(">").trimStart() }).append("</p>\n")
                }
                BULLET.containsMatchIn(firstLine) || NUMBERED.containsMatchIn(firstLine) -> {
                    closeQuote()
                    val numbered = NUMBERED.containsMatchIn(firstLine)
                    val tag = if (numbered) "ol" else "ul"
                    if (openList != tag) {
                        closeList()
                        out.append("<").append(tag).append(">\n")
                        openList = tag
                    }
                    val item = (if (numbered) NUMBERED else BULLET).replaceFirst(paragraph.trimStart(), "")
                    out.append("<li>").append(lines(item)).append("</li>\n")
                }
                else -> {
                    closeList(); closeQuote()
                    out.append("<p>").append(lines(paragraph)).append("</p>\n")
                }
            }
        }
        closeList()
        closeQuote()
        return out.toString()
    }

    /** **bold** and *italic* within a line (markers must hug the text), with \* and \\ escapes. */
    private fun inline(line: String): String {
        var s = line.replace("\\\\", "\uE000").replace("\\*", "\uE001")
        s = escape(s)
        s = BOLD.replace(s) { "<strong>" + it.groupValues[1] + "</strong>" }
        s = ITALIC.replace(s) { "<em>" + it.groupValues[1] + "</em>" }
        return s.replace("\uE000", "\\").replace("\uE001", "*")
    }

    private fun xhtmlPage(title: String, language: String, body: String): String {
        val t = escape(title)
        val lang = escape(language)
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$lang">
<head>
<title>$t</title>
<link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
<h1>$t</h1>
$body</body>
</html>
"""
    }

    /** Picks the file extension from the image's leading bytes (which decides its EPUB media type). */
    private fun imageExtension(bytes: ByteArray): String {
        fun startsWith(vararg sig: Int) = bytes.size >= sig.size && sig.indices.all { bytes[it] == sig[it].toByte() }
        return when {
            startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
            startsWith(0x89, 0x50, 0x4E, 0x47) -> "png"
            startsWith(0x47, 0x49, 0x46, 0x38) -> "gif"
            else -> throw IllegalArgumentException("Unsupported image format; expected JPEG, PNG or GIF")
        }
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Drops characters that XML 1.0 forbids (control characters, stray surrogates), which would make the book invalid. */
    private fun sanitize(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val allowed = cp == 0x9 || cp == 0xA || cp == 0xD ||
                cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF
            if (allowed) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private val PARAGRAPH_BREAK = Regex("\n[ \t]*\n+")
    private val HEADING = Regex("^(#{1,6})\\s+")
    private val BULLET = Regex("^•\\s+")
    private val NUMBERED = Regex("^\\d{1,3}\\.\\s+")
    private val BOLD = Regex("\\*\\*(\\S(?:.*?\\S)?)\\*\\*")
    private val ITALIC = Regex("\\*(\\S(?:.*?\\S)?)\\*")

    private const val STYLESHEET = """h1 { text-align: center; margin: 1em 0 1.5em 0; }
h2 { margin: 1.2em 0 0.6em 0; }
h3 { margin: 1em 0 0.5em 0; }
p { margin: 0 0 0.8em 0; text-indent: 0; }
blockquote { margin: 0.8em 1.5em; font-style: italic; }
ul, ol { margin: 0 0 0.8em 1.5em; padding: 0; }
li { margin: 0 0 0.3em 0; }
div.image { text-align: center; margin: 1em 0; page-break-inside: avoid; }
img { max-width: 100%; }
"""
}
