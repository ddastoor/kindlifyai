package io.github.ddastoor.kindlify.data

import android.util.Log
import io.github.ddastoor.kindlify.reader.ArticleExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import java.io.IOException
import java.util.UUID

/** A page after its JavaScript has run: the final address and the resulting HTML. */
data class RenderedPage(val url: String, val html: String)

/** Loads a page in a real browser engine (so its JavaScript runs); null if that didn't work. */
fun interface PageRenderer {
    suspend fun render(url: String): RenderedPage?
}

/**
 * Downloads a web page in reader mode and turns it into canvas blocks (formatted text and images).
 *
 * The page is first fetched directly (fast, no JavaScript). If that finds little or no article
 * text, or the site refuses plain downloads (403/429/503), and a [renderer] is available, the page
 * is loaded again in a hidden browser so its JavaScript can build the content, and the better of
 * the two results is used.
 */
class WebImporter(private val importer: ContentImporter, private val renderer: PageRenderer? = null) {

    data class Page(val title: String?, val blocks: List<Block>)

    private class Found(val article: ArticleExtractor.Article, val pageUrl: String)

    suspend fun fetch(projectId: String, url: String): Page {
        val browser = renderer
        var found: Found? = null
        var refused: HttpStatusException? = null
        try {
            val response = withContext(Dispatchers.IO) { connect(url, referrer = null).maxBodySize(MAX_PAGE_BYTES).execute() }
            val type = response.contentType().orEmpty().lowercase()

            // A link straight to a picture: just add the picture.
            if (type.startsWith("image/")) {
                return withContext(Dispatchers.IO) {
                    Page(null, listOf(importer.importImageBytes(projectId, response.bodyAsBytes())))
                }
            }
            if (type.isNotEmpty() && "html" !in type && "xml" !in type) {
                throw IOException("that link isn't a web page ($type)")
            }
            found = withContext(Dispatchers.IO) {
                Found(ArticleExtractor.extract(response.parse()), response.url().toString())
            }
        } catch (e: HttpStatusException) {
            if (browser == null || e.statusCode !in TRY_IN_BROWSER) throw e
            refused = e
        }

        // Pages that build their text with JavaScript (or turn away plain downloads): try a browser.
        if (browser != null && (found == null || textLength(found.article) < MIN_ARTICLE_CHARS)) {
            val rendered = runCatching { browser.render(url) }
                .onFailure { Log.w(TAG, "Couldn't render $url in the browser", it) }
                .getOrNull()
            if (rendered != null) {
                val second = withContext(Dispatchers.Default) {
                    runCatching { ArticleExtractor.extract(Jsoup.parse(rendered.html, rendered.url)) }.getOrNull()
                }
                if (second != null && (found == null || textLength(second) > textLength(found.article))) {
                    found = Found(second, rendered.url)
                }
            }
        }

        val result = found ?: throw (refused ?: IOException("the page couldn't be loaded"))
        return withContext(Dispatchers.IO) { toBlocks(projectId, result.article, result.pageUrl) }
    }

    private suspend fun toBlocks(projectId: String, article: ArticleExtractor.Article, pageUrl: String): Page {
        // Download the article's pictures, a few at a time, skipping icons and anything that fails.
        val imageUrls = article.parts.filterIsInstance<ArticleExtractor.Part.Image>()
            .map { it.url }.distinct().take(MAX_IMAGES)
        val gate = Semaphore(4)
        val images: Map<String, Block.Image?> = coroutineScope {
            imageUrls.map { imageUrl ->
                async {
                    imageUrl to gate.withPermit {
                        runCatching { download(projectId, imageUrl, pageUrl) }
                            .onFailure { if (it !is ContentImporter.ImageTooSmall) Log.w(TAG, "Skipped picture $imageUrl", it) }
                            .getOrNull()
                    }
                }
            }.awaitAll().toMap()
        }

        // "From site · by author · date" goes first, then the article in order.
        val parts = listOfNotNull(article.source?.let { ArticleExtractor.Part.Text(it) }) + article.parts
        val blocks = mutableListOf<Block>()
        val placed = mutableSetOf<String>()
        for (part in parts) {
            when (part) {
                is ArticleExtractor.Part.Text -> {
                    // Text runs that were split by a picture we skipped get joined back up.
                    val previous = blocks.lastOrNull()
                    if (previous is Block.Text) {
                        blocks[blocks.lastIndex] = previous.copy(text = previous.text + "\n\n" + part.text)
                    } else {
                        blocks += Block.Text(UUID.randomUUID().toString(), part.text, formatted = true)
                    }
                }
                is ArticleExtractor.Part.Image ->
                    if (placed.add(part.url)) images[part.url]?.let { blocks += it }
            }
        }
        // Just a source line and no article means nothing readable was found.
        val hasArticle = article.parts.isNotEmpty()
        return Page(article.title, if (hasArticle) blocks else emptyList())
    }

    private fun textLength(article: ArticleExtractor.Article) =
        article.parts.sumOf { if (it is ArticleExtractor.Part.Text) it.text.length else 0 }

    private fun download(projectId: String, imageUrl: String, pageUrl: String): Block.Image {
        val bytes = connect(imageUrl, referrer = pageUrl)
            .maxBodySize(ContentImporter.MAX_IMAGE_BYTES)
            .execute()
            .bodyAsBytes()
        return importer.importImageBytes(projectId, bytes, minSide = MIN_IMAGE_SIDE)
    }

    private fun connect(url: String, referrer: String?): Connection =
        Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(20_000)
            .followRedirects(true)
            .ignoreContentType(true)
            .apply { if (referrer != null) referrer(referrer) }

    companion object {
        private const val TAG = "Kindlify"
        const val MAX_IMAGES = 40
        /** Pictures smaller than this (px, either side) are icons, avatars or trackers: skipped. */
        const val MIN_IMAGE_SIDE = 100
        /** Less article text than this from the direct download suggests the page needs JavaScript. */
        const val MIN_ARTICLE_CHARS = 300
        /** Answers that often mean "no bots", where a real browser may still get the page. */
        private val TRY_IN_BROWSER = setOf(403, 429, 503)
        private const val MAX_PAGE_BYTES = 15_000_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}
