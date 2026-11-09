package io.github.ddastoor.kindlify

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ddastoor.kindlify.data.Block
import io.github.ddastoor.kindlify.data.ProjectStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile

/** "Fetch contents" end to end, against a small local web server serving a sample news page. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class FetchContentsTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val clipboard get() = app.getSystemService(ClipboardManager::class.java)
    private val store get() = ProjectStore(app.filesDir)
    private lateinit var server: TinyWebServer

    @Before
    fun setUp() {
        resetFileProviderCache()
        val page = javaClass.getResource("/web/story.html")!!.readBytes()
        val pictures = mapOf(
            "/img/harbour.jpg" to photo(1200, 800),
            "/img/boats.jpg" to photo(900, 600),
            "/img/icon.png" to photo(16, 16),
            "/img/ad-banner.jpg" to photo(728, 90),
        )
        server = TinyWebServer(
            mapOf("/story.html" to ("text/html; charset=utf-8" to page)) +
                pictures.mapValues { "image/jpeg" to it.value },
        )
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun fetchContents_pullsTheArticleInBelowTheLink() {
        val link = "http://127.0.0.1:${server.port}/story.html"

        compose.onNodeWithText("New canvas").performClick()
        compose.onNodeWithText("Fetch contents").assertIsNotEnabled()

        // Text that merely mentions a link doesn't count.
        paste("Have a look at $link")
        compose.onNodeWithText("Fetch contents").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Item options").performClick()
        compose.onNodeWithText("Remove").performClick()

        // Just the link: the button becomes available.
        paste(link)
        compose.onNodeWithText("Fetch contents").assertIsEnabled()
        compose.screenshot("09a-link-only")

        compose.onNodeWithText("Fetch contents").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Page contents added below the link.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Fetch contents").assertIsNotEnabled()

        val project = store.list().single()
        assertEquals("canvas takes the article's title", "A Walk Through the Old Harbour", project.title)
        assertEquals("link kept at the top", link, (project.blocks[0] as Block.Text).text)
        assertTrue("separator below the link", project.blocks[1] is Block.Separator)
        val pictures = project.blocks.filterIsInstance<Block.Image>().map { it.width to it.height }
        assertEquals("article pictures only, no logo/ad", listOf(1200 to 800, 900 to 600), pictures)
        assertEquals("the separator names the article's chapter", "A Walk Through the Old Harbour", (project.blocks[1] as Block.Separator).title)
        val fetched = project.blocks.drop(2).filterIsInstance<Block.Text>()
        assertTrue("fetched text keeps its formatting", fetched.all { it.formatted })
        assertTrue(fetched.first().text, fetched.first().text.startsWith("*From The Daily Example · by Jane Writer · "))
        val text = fetched.joinToString("\n\n") { it.text }
        assertTrue(text, text.contains("The old harbour wakes up slowly."))
        assertTrue(text, text.contains("• The lighthouse at the end of the north pier."))
        for (junk in listOf("Buy cheap watches", "Subscribe now", "Cookie settings", "Comments (42)", "All rights reserved")) {
            assertFalse("should drop \"$junk\"", text.contains(junk))
        }

        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        compose.screenshot("09-fetched-article")

        // The fetched article turns into a valid EPUB, titled after it.
        compose.onNodeWithText("Create EPUB").performClick()
        compose.onNodeWithText("Book title").assertTextContains("A Walk Through the Old Harbour")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("EPUB ready.", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val epub = store.epubFile(store.list().single())!!
        ZipFile(epub).use { zip ->
            fun read(name: String) = zip.getInputStream(zip.getEntry(name)).reader().readText()
            // The link stays on the first page; the article is its own chapter, listed in the table of contents.
            assertTrue(read("OEBPS/content.xhtml").contains(link))
            val chapter = read("OEBPS/chapter-002.xhtml")
            assertTrue(chapter, chapter.contains("<h1>A Walk Through the Old Harbour</h1>"))
            assertEquals(2, Regex("<img ").findAll(chapter).count())
            assertTrue(read("OEBPS/toc.ncx").contains("<text>A Walk Through the Old Harbour</text>"))
            // Formatting and the source line come through as real formatting.
            for (expected in listOf(
                "<p><em>From The Daily Example · by Jane Writer · ",
                "The museum's <strong>best exhibit</strong> is a <em>hand-drawn</em> map",
                "<h2>What to see</h2>",
                "<blockquote>",
                "<li><strong>Tip:</strong> go early, before the crowds.</li>",
                "<ol>\n<li>Take the coastal train to Harbour Halt.</li>",
                "open daily* (*except Mondays).",
            )) assertTrue("chapter should contain: $expected\n$chapter", chapter.contains(expected))
        }
        epub.copyTo(File("build/e2e/fetched-article.epub").apply { parentFile!!.mkdirs() }, overwrite = true)
    }

    @Test
    fun fetchContents_reportsPagesThatCantBeFetched() {
        compose.onNodeWithText("New canvas").performClick()
        paste("http://127.0.0.1:${server.port}/missing.html")
        compose.onNodeWithText("Fetch contents").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Couldn't fetch the page", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, store.list().single().blocks.size)
    }

    private fun paste(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
        compose.onNodeWithText("Paste").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun photo(width: Int, height: Int): ByteArray {
        val file = File(app.cacheDir, "photo-$width-$height.jpg")
        writeTestPhoto(file, width, height)
        return file.readBytes()
    }
}
