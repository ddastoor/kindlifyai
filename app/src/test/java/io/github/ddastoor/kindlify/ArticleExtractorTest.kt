package io.github.ddastoor.kindlify

import io.github.ddastoor.kindlify.reader.ArticleExtractor
import io.github.ddastoor.kindlify.reader.ArticleExtractor.Part
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reader mode on a sample news page (plain JVM, no Android). */
class ArticleExtractorTest {

    private val page = javaClass.getResource("/web/story.html")!!.readText()

    private fun extract() = ArticleExtractor.extract(Jsoup.parse(page, "https://news.example.com/2026/harbour.html"))

    @Test
    fun keepsTheArticle_dropsMenusAdsCommentsAndFooter() {
        val article = extract()
        val text = article.parts.filterIsInstance<Part.Text>().joinToString("\n\n") { it.text }

        assertEquals("A Walk Through the Old Harbour", article.title)
        assertFalse("headline isn't repeated as the first paragraph", text.startsWith("A Walk Through the Old Harbour"))
        assertTrue(text, text.contains("The old harbour wakes up slowly."))
        assertTrue(text, text.contains("By seven o'clock the fish market is in full swing."))
        assertTrue(text, text.contains("just as it has for centuries."))
        assertTrue(text, text.contains("The harbour at dawn, before the market opens."))

        for (junk in listOf("Buy cheap watches", "ADVERTISEMENT", "Subscribe now", "Cookie settings", "Comments (42)", "All rights reserved")) {
            assertFalse("should drop \"$junk\":\n$text", text.contains(junk))
        }
    }

    @Test
    fun keepsPicturesInReadingOrder_withAbsoluteUrls_includingLazyLoadedOnes() {
        val parts = extract().parts
        val images = parts.filterIsInstance<Part.Image>().map { it.url }
        assertEquals(
            listOf("https://news.example.com/img/harbour.jpg", "https://news.example.com/img/boats.jpg"),
            images,
        )
        // The first picture comes after the first paragraph, the lazy one before the list.
        val firstImage = parts.indexOfFirst { it is Part.Image }
        assertTrue((parts[firstImage - 1] as Part.Text).text.contains("The old harbour wakes up slowly."))
        val lazyImage = parts.indexOfLast { it is Part.Image }
        assertTrue((parts[lazyImage + 1] as Part.Text).text.contains("Things worth seeing"))
    }

    @Test
    fun keepsThePortInPictureUrls() {
        val article = ArticleExtractor.extract(Jsoup.parse(page, "http://news.example.com:8080/2026/harbour.html"))
        assertEquals(
            listOf("http://news.example.com:8080/img/harbour.jpg", "http://news.example.com:8080/img/boats.jpg"),
            article.parts.filterIsInstance<Part.Image>().map { it.url },
        )
    }

    @Test
    fun keepsParagraphsAndListItems() {
        val text = extract().parts.filterIsInstance<Part.Text>().joinToString("\n\n") { it.text }
        assertTrue(text, text.contains("• The fish market, early in the morning.\n\n• The 1820 harbour map in the museum."))
        // Paragraphs are separated by blank lines (which become separate paragraphs in the EPUB),
        // and whitespace inside them is collapsed.
        assertTrue(text, text.contains("The old harbour wakes up slowly. Long before the first tourists arrive, the fishing boats are already"))
    }
}
