package io.github.ddastoor.kindlify.reader

import net.dankito.readability4j.extended.Readability4JExtended
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.net.URI

/**
 * "Reader mode" for a web page: keeps the main article (its text and pictures, in order)
 * and drops menus, ads, side panels, comments and footers.
 *
 * The hard part is done by Readability4J, a port of Mozilla's Readability (the engine
 * behind Firefox's Reader View). This class only prepares lazy-loaded images first and
 * then turns the cleaned-up article into plain-text paragraphs and image links.
 */
object ArticleExtractor {

    sealed interface Part {
        /** One or more paragraphs, separated by blank lines. */
        data class Text(val text: String) : Part

        /** An absolute http(s) image URL. */
        data class Image(val url: String) : Part
    }

    data class Article(val title: String?, val parts: List<Part>)

    /** [document] must have its location (base URL) set, as jsoup does when it fetches a page. */
    fun extract(document: Document): Article {
        val pageUrl = document.location()
        useRealImageSources(document, pageUrl)
        val article = Readability4JExtended(pageUrl, document).parse()
        val title = article.title?.trim()?.takeIf { it.isNotEmpty() }
        val content = article.articleContent ?: return Article(title, emptyList())
        return Article(title, withoutLeadingTitle(toParts(content, pageUrl), title))
    }

    /** The book already shows the title as its heading, so drop it if the article repeats it first. */
    private fun withoutLeadingTitle(parts: List<Part>, title: String?): List<Part> {
        val first = parts.firstOrNull() as? Part.Text ?: return parts
        if (title == null) return parts
        val paragraphs = first.text.split("\n\n")
        if (!paragraphs.first().trim().equals(title.trim(), ignoreCase = true)) return parts
        val rest = paragraphs.drop(1).joinToString("\n\n")
        return if (rest.isBlank()) parts.drop(1) else listOf(Part.Text(rest)) + parts.drop(1)
    }

    /**
     * Lazy-loading pages often put a placeholder in `src` and the real picture in
     * `data-src` or `srcset`. Point `src` at the real one so it survives extraction,
     * already made absolute: Readability4J's own fix-up of relative URLs drops the
     * port number (e.g. example.com:8080), which would break those pictures.
     */
    private fun useRealImageSources(document: Document, pageUrl: String) {
        for (img in document.select("img")) {
            val best = bestSource(img) ?: continue
            img.attr("src", resolve(pageUrl, best) ?: best)
            // Otherwise Readability4J copies these (still relative) back over src.
            (LAZY_ATTRIBUTES + listOf("srcset", "data-srcset")).forEach { img.removeAttr(it) }
        }
    }

    private fun bestSource(img: Element): String? {
        largestFromSrcset(img.attr("srcset").ifBlank { img.attr("data-srcset") })?.let { return it }
        LAZY_ATTRIBUTES.map { img.attr(it).trim() }.firstOrNull { it.isUsable() }?.let { return it }
        return img.attr("src").trim().takeIf { it.isUsable() }
    }

    /** From "a.jpg 480w, b.jpg 1080w" (or 1x/2x), picks the biggest one up to ~2400px wide. */
    private fun largestFromSrcset(srcset: String): String? {
        val candidates = srcset.split(",").mapNotNull { entry ->
            val bits = entry.trim().split(Regex("\\s+"))
            val url = bits.firstOrNull()?.takeIf { it.isUsable() } ?: return@mapNotNull null
            val descriptor = bits.getOrNull(1).orEmpty()
            val size = when {
                descriptor.endsWith("w") -> descriptor.dropLast(1).toFloatOrNull()
                descriptor.endsWith("x") -> descriptor.dropLast(1).toFloatOrNull()?.times(1000)
                else -> 1f
            } ?: 1f
            url to size
        }
        if (candidates.isEmpty()) return null
        return (candidates.filter { it.second <= 2400 }.maxByOrNull { it.second }
            ?: candidates.minByOrNull { it.second })?.first
    }

    private fun String.isUsable() = isNotBlank() && !startsWith("data:")

    /** Walks the article in document order, turning it into text runs and images. */
    private fun toParts(root: Element, pageUrl: String): List<Part> {
        val parts = mutableListOf<Part>()
        val paragraphs = mutableListOf<String>()
        val line = StringBuilder()
        var bulletPending = false

        fun endParagraph() {
            val text = line.lines().joinToString("\n") { it.replace(SPACES, " ").trim() }.trim()
            if (text.isNotEmpty()) paragraphs += text
            line.setLength(0)
        }

        fun endTextRun() {
            endParagraph()
            if (paragraphs.isNotEmpty()) parts += Part.Text(paragraphs.joinToString("\n\n"))
            paragraphs.clear()
        }

        fun appendText(text: String) {
            if (bulletPending && text.isNotBlank() && line.isBlank()) {
                line.append("• ")
                bulletPending = false
            }
            line.append(text)
        }

        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when {
                    node is TextNode -> appendText(if (isInsidePre(node)) node.wholeText else node.text())
                    node !is Element -> Unit
                    node.normalName() == "img" -> resolve(pageUrl, node.attr("src"))?.let {
                        endTextRun()
                        parts += Part.Image(it)
                    }
                    node.normalName() == "br" -> line.append('\n')
                    node.isBlock -> {
                        endParagraph()
                        if (node.normalName() == "li") bulletPending = true
                    }
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.isBlock) endParagraph()
            }
        }, root)
        endTextRun()
        return parts
    }

    private fun isInsidePre(node: TextNode): Boolean {
        var parent = node.parent()
        while (parent is Element) {
            if (parent.normalName() == "pre") return true
            parent = parent.parent()
        }
        return false
    }

    private fun resolve(base: String, src: String): String? {
        if (!src.isUsable()) return null
        val absolute = runCatching { URI(base).resolve(src.trim().replace(" ", "%20")).toString() }.getOrNull()
        return absolute?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private val LAZY_ATTRIBUTES = listOf("data-src", "data-original", "data-lazy-src", "data-url")
    private val SPACES = Regex("[ \\t\\u00A0]+")
}
