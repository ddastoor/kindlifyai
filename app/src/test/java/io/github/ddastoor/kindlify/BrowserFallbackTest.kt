package io.github.ddastoor.kindlify

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ddastoor.kindlify.data.Block
import io.github.ddastoor.kindlify.data.ContentImporter
import io.github.ddastoor.kindlify.data.PageRenderer
import io.github.ddastoor.kindlify.data.ProjectStore
import io.github.ddastoor.kindlify.data.RenderedPage
import io.github.ddastoor.kindlify.data.WebImporter
import kotlinx.coroutines.runBlocking
import org.jsoup.HttpStatusException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * When Fetch contents falls back to loading the page in a (hidden) browser, and when it doesn't.
 * The browser here is a stand-in that returns the finished page, since Robolectric can't run a
 * real WebView; the real one (WebViewRenderer) is only exercised on a phone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserFallbackTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: TinyWebServer
    private val base get() = "http://127.0.0.1:${server.port}"
    private val story = javaClass.getResource("/web/story.html")!!.readText()
    private var browserCalls = 0

    @Before
    fun setUp() {
        resetFileProviderCache()
        // A page whose text is only built by JavaScript: the plain download is an empty shell.
        val shell = "<html><head><title>Loading…</title></head><body><div id=\"root\"></div><script src=\"/app.js\"></script></body></html>"
        server = TinyWebServer(
            routes = mapOf(
                "/app.html" to ("text/html" to shell.toByteArray()),
                "/story.html" to ("text/html; charset=utf-8" to story.toByteArray()),
                "/img/harbour.jpg" to ("image/jpeg" to photo(1200, 800)),
                "/img/boats.jpg" to ("image/jpeg" to photo(900, 600)),
            ),
            statuses = mapOf("/blocked.html" to 403),
        )
    }

    @After
    fun tearDown() = server.close()

    /** A stand-in browser that "runs the JavaScript", giving the full article. */
    private val browser = PageRenderer { _ ->
        browserCalls++
        RenderedPage("$base/story.html", story)
    }

    private fun importer() = WebImporter(ContentImporter(app, ProjectStore(app.filesDir)), browser)

    @Test
    fun pageThatNeedsJavaScript_isLoadedInTheBrowser() = runBlocking {
        val page = importer().fetch("p", "$base/app.html")
        assertEquals(1, browserCalls)
        assertEquals("A Walk Through the Old Harbour", page.title)
        val text = page.blocks.filterIsInstance<Block.Text>().joinToString { it.text }
        assertTrue(text, text.contains("The old harbour wakes up slowly."))
        assertEquals(2, page.blocks.count { it is Block.Image })
    }

    @Test
    fun ordinaryPage_doesNotUseTheBrowser() = runBlocking {
        val page = importer().fetch("p", "$base/story.html")
        assertEquals(0, browserCalls)
        assertEquals("A Walk Through the Old Harbour", page.title)
    }

    @Test
    fun siteRefusingPlainDownloads_isTriedInTheBrowser() = runBlocking {
        val page = importer().fetch("p", "$base/blocked.html")
        assertEquals(1, browserCalls)
        assertEquals("A Walk Through the Old Harbour", page.title)
    }

    @Test
    fun missingPage_isReportedWithoutTryingTheBrowser(): Unit = runBlocking {
        try {
            importer().fetch("p", "$base/missing.html")
            fail("expected an error")
        } catch (e: HttpStatusException) {
            assertEquals(404, e.statusCode)
        }
        assertEquals(0, browserCalls)
    }

    @Test
    fun browserNotHelping_keepsThePlainResult() = runBlocking {
        val noBrowser = WebImporter(ContentImporter(app, ProjectStore(app.filesDir))) { null }
        val page = noBrowser.fetch("p", "$base/app.html")
        assertTrue("nothing readable in the empty shell", page.blocks.isEmpty())
    }

    private fun photo(width: Int, height: Int): ByteArray {
        val file = File(app.cacheDir, "photo-$width-$height.jpg")
        writeTestPhoto(file, width, height)
        return file.readBytes()
    }
}
