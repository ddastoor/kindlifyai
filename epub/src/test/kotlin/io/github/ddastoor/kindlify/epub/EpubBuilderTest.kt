package io.github.ddastoor.kindlify.epub

import com.adobe.epubcheck.api.EpubCheck
import com.adobe.epubcheck.util.DefaultReportImpl
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpubBuilderTest {

    private val outDir = File(System.getProperty("epub.outputDir") ?: "build/test-epubs").apply { mkdirs() }

    @Test
    fun `book with text and images passes epubcheck with no errors or warnings`() {
        val png = image("png", BufferedImage.TYPE_INT_ARGB, 400, 300)
        val jpg = image("jpg", BufferedImage.TYPE_INT_RGB, 640, 480)
        val epub = File(outDir, "sample.epub")

        epub.outputStream().use { out ->
            EpubBuilder.write(
                title = "Notes & <Clippings> \"2026\"",
                blocks = listOf(
                    EpubBlock.Text("First paragraph with an ampersand & a <tag>.\nSecond line of it.\n\n\nNew paragraph \u0001with a control char, émojis 📚 and ünïcödé."),
                    EpubBlock.Image(png),
                    EpubBlock.Text("Text between the images."),
                    EpubBlock.Image(jpg),
                    EpubBlock.Text("   \n\n  "),
                ),
                out = out,
                language = "en-GB",
            )
        }

        val report = DefaultReportImpl(epub.name)
        EpubCheck(epub, report).doValidate()
        assertEquals(0, report.fatalErrorCount, "epubcheck fatal errors")
        assertEquals(0, report.errorCount, "epubcheck errors")
        assertEquals(0, report.warningCount, "epubcheck warnings")

        ZipFile(epub).use { zip ->
            val first = zip.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            val names = zip.entries().toList().map { it.name }
            assertTrue(names.any { it.endsWith("images/image001.png") }, names.toString())
            assertTrue(names.any { it.endsWith("images/image002.jpg") }, names.toString())
            assertTrue(names.none { it.contains("cover", ignoreCase = true) }, "no cover expected: $names")
            // No chapter breaks: a single page headed by the book title, plain text as before.
            assertEquals(1, names.count { it.endsWith(".xhtml") }, names.toString())
            val page = zip.getInputStream(zip.getEntry("OEBPS/content.xhtml")).reader().readText()
            assertTrue(page.contains("<h1>Notes &amp; &lt;Clippings&gt; &quot;2026&quot;</h1>"), page)
            assertTrue(page.contains("<div class=\"image\"><img src=\"images/image001.png\" alt=\"\"/></div>\n<p>Text between the images.</p>"), page)
        }
    }

    @Test
    fun `chapter breaks make separate pages and table of contents entries`() {
        val jpg = image("jpg", BufferedImage.TYPE_INT_RGB, 320, 200)
        val epub = File(outDir, "chapters.epub")
        epub.outputStream().use { out ->
            EpubBuilder.write(
                title = "Weekend reading",
                blocks = listOf(
                    EpubBlock.Text("https://example.com/harbour"),
                    EpubBlock.ChapterBreak("A Walk Through the Old Harbour"),
                    EpubBlock.Text("The old harbour wakes up slowly."),
                    EpubBlock.Image(jpg),
                    EpubBlock.ChapterBreak(),
                    EpubBlock.Text("Second shared paragraph, which is long enough that its chapter title gets shortened nicely.\nMore."),
                    EpubBlock.ChapterBreak("Empty chapter"),
                    EpubBlock.Text("  "),
                    EpubBlock.ChapterBreak(),
                    EpubBlock.Text("## A heading first\n\nThen **text**.", formatted = true),
                ),
                out = out,
            )
        }
        assertValid(epub)
        ZipFile(epub).use { zip ->
            val pages = zip.entries().toList().map { it.name }.filter { it.endsWith(".xhtml") }.sorted()
            assertEquals(listOf("OEBPS/chapter-002.xhtml", "OEBPS/chapter-003.xhtml", "OEBPS/chapter-004.xhtml", "OEBPS/content.xhtml"), pages)
            fun read(name: String) = zip.getInputStream(zip.getEntry(name)).reader().readText()
            assertTrue(read("OEBPS/content.xhtml").contains("<h1>Weekend reading</h1>"))
            val article = read("OEBPS/chapter-002.xhtml")
            assertTrue(article.contains("<h1>A Walk Through the Old Harbour</h1>"), article)
            assertTrue(article.contains("images/image001.jpg"), article)
            val toc = read("OEBPS/toc.ncx")
            for (entry in listOf("Weekend reading", "A Walk Through the Old Harbour", "Second shared paragraph, which is long enough that its…", "A heading first")) {
                assertTrue(toc.contains("<text>$entry</text>"), "table of contents should list \"$entry\":\n$toc")
            }
            assertTrue(!toc.contains("Empty chapter"), "empty chapters are left out")
        }
    }

    @Test
    fun `formatted text becomes headings, quotes, lists, bold and italic`() {
        val xhtml = EpubBuilder.markupToXhtml(
            "## Big heading\n\n### Small heading\n\nA **bold** and *italic* line, 5 \\* 3 = 15, a\\\\b.\n" +
                "Second line.\n\n> Quoted\n\n> still quoted\n\n• one\n\n• two\n\n1. first\n\n2. second\n\n" +
                "Unbalanced * star and ** stars & <tags>",
        )
        assertEquals(
            "<h2>Big heading</h2>\n<h3>Small heading</h3>\n" +
                "<p>A <strong>bold</strong> and <em>italic</em> line, 5 * 3 = 15, a\\b.<br/>\nSecond line.</p>\n" +
                "<blockquote>\n<p>Quoted</p>\n<p>still quoted</p>\n</blockquote>\n" +
                "<ul>\n<li>one</li>\n<li>two</li>\n</ul>\n<ol>\n<li>first</li>\n<li>second</li>\n</ol>\n" +
                "<p>Unbalanced * star and ** stars &amp; &lt;tags&gt;</p>\n",
            xhtml,
        )
    }

    @Test
    fun `plain text is never treated as formatting`() {
        val xhtml = EpubBuilder.textToXhtml("## not a heading\n\n> not a quote **not bold** *not italic*\n\n• not a list")
        assertEquals(
            "<p>## not a heading</p>\n<p>&gt; not a quote **not bold** *not italic*</p>\n<p>• not a list</p>\n",
            xhtml,
        )
    }

    @Test
    fun `book with formatted text and chapters passes epubcheck`() {
        val epub = File(outDir, "formatted.epub")
        epub.outputStream().use { out ->
            EpubBuilder.write(
                title = "Formatted",
                blocks = listOf(
                    EpubBlock.ChapterBreak("Starts with a chapter"),
                    EpubBlock.Text("*From The Daily Example · by Jane Writer · 8 Nov 2026*\n\n## Heading\n\n> A quote\n\n• item\n\n1. numbered\n\nText with **bold**.", formatted = true),
                ),
                out = out,
            )
        }
        assertValid(epub)
        ZipFile(epub).use { zip ->
            val pages = zip.entries().toList().map { it.name }.filter { it.endsWith(".xhtml") }
            assertEquals(listOf("OEBPS/content.xhtml"), pages, "a break at the very start doesn't leave an empty page")
            val page = zip.getInputStream(zip.getEntry("OEBPS/content.xhtml")).reader().readText()
            assertTrue(page.contains("<h1>Starts with a chapter</h1>"), page)
            assertTrue(page.contains("<p><em>From The Daily Example · by Jane Writer · 8 Nov 2026</em></p>"), page)
        }
    }

    private fun assertValid(epub: File) {
        val report = DefaultReportImpl(epub.name)
        EpubCheck(epub, report).doValidate()
        assertEquals(0, report.fatalErrorCount, "epubcheck fatal errors")
        assertEquals(0, report.errorCount, "epubcheck errors")
        assertEquals(0, report.warningCount, "epubcheck warnings")
    }

    @Test
    fun `text becomes escaped paragraphs and line breaks`() {
        val xhtml = EpubBuilder.textToXhtml("a & b\r\nc < d\n\n\n e \"f\" \u0000")
        assertEquals("<p>a &amp; b<br/>\nc &lt; d</p>\n<p> e &quot;f&quot; </p>\n", xhtml)
    }

    private fun image(format: String, type: Int, w: Int, h: Int): File {
        val img = BufferedImage(w, h, type)
        val g = img.createGraphics()
        g.color = Color(30, 90, 160)
        g.fillRect(0, 0, w, h)
        g.color = Color.ORANGE
        g.fillOval(w / 4, h / 4, w / 2, h / 2)
        g.dispose()
        val file = File(outDir, "test.$format")
        ImageIO.write(img, format, file)
        return file
    }
}
