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
    data class Text(val text: String) : EpubBlock

    /** A JPEG, PNG or GIF file. */
    data class Image(val file: File) : EpubBlock

    /** A horizontal dividing line. */
    data object Separator : EpubBlock
}

/**
 * Builds an EPUB from canvas content.
 *
 * epub4j (a maintained fork of epublib) does the EPUB work: the zip layout,
 * the `mimetype` entry, container.xml, the OPF package document and the NCX
 * table of contents. This class only turns the canvas into one XHTML page
 * and hands that page, a stylesheet and the images to epub4j.
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

        val body = StringBuilder()
        var imageCount = 0
        for (block in blocks) {
            when (block) {
                is EpubBlock.Text -> body.append(textToXhtml(block.text))
                is EpubBlock.Image -> {
                    val bytes = block.file.readBytes()
                    imageCount++
                    val href = "images/image" + imageCount.toString().padStart(3, '0') + "." + imageExtension(bytes)
                    book.addResource(Resource(bytes, href))
                    body.append("<div class=\"image\"><img src=\"").append(href).append("\" alt=\"\"/></div>\n")
                }
                EpubBlock.Separator -> body.append("<hr/>\n")
            }
        }

        val page = Resource(xhtmlPage(cleanTitle, language, body.toString()).toByteArray(Charsets.UTF_8), "content.xhtml")
        book.addSection(cleanTitle, page)
        // EPUB 2 requires at least one guide entry; this one also tells Kindle where reading starts.
        book.guide.addReference(GuideReference(page, GuideReference.TEXT, cleanTitle))
        EpubWriter().write(book, out)
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

    private const val STYLESHEET = """h1 { text-align: center; margin: 1em 0 1.5em 0; }
p { margin: 0 0 0.8em 0; text-indent: 0; }
div.image { text-align: center; margin: 1em 0; page-break-inside: avoid; }
img { max-width: 100%; }
hr { margin: 1.5em 10%; border: 0; border-top: 1px solid #888; }
"""
}
