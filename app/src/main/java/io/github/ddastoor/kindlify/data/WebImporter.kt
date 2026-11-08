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
import org.jsoup.Jsoup
import java.io.IOException
import java.util.UUID

/** Downloads a web page in reader mode and turns it into canvas blocks (text and images). */
class WebImporter(private val importer: ContentImporter) {

    data class Page(val title: String?, val blocks: List<Block>)

    suspend fun fetch(projectId: String, url: String): Page = withContext(Dispatchers.IO) {
        val response = connect(url, referrer = null).maxBodySize(MAX_PAGE_BYTES).execute()
        val type = response.contentType().orEmpty().lowercase()

        // A link straight to a picture: just add the picture.
        if (type.startsWith("image/")) {
            return@withContext Page(null, listOf(importer.importImageBytes(projectId, response.bodyAsBytes())))
        }
        if (type.isNotEmpty() && "html" !in type && "xml" !in type) {
            throw IOException("that link isn't a web page ($type)")
        }

        val article = ArticleExtractor.extract(response.parse())
        val pageUrl = response.url().toString()

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

        val blocks = mutableListOf<Block>()
        val placed = mutableSetOf<String>()
        for (part in article.parts) {
            when (part) {
                is ArticleExtractor.Part.Text -> {
                    // Text runs that were split by a picture we skipped get joined back up.
                    val previous = blocks.lastOrNull()
                    if (previous is Block.Text) {
                        blocks[blocks.lastIndex] = previous.copy(text = previous.text + "\n\n" + part.text)
                    } else {
                        blocks += Block.Text(UUID.randomUUID().toString(), part.text)
                    }
                }
                is ArticleExtractor.Part.Image ->
                    if (placed.add(part.url)) images[part.url]?.let { blocks += it }
            }
        }
        Page(article.title, blocks)
    }

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
        private const val MAX_PAGE_BYTES = 15_000_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}
