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
        }
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
