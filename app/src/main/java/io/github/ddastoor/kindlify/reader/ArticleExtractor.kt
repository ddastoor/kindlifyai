package io.github.ddastoor.kindlify.reader

import net.dankito.readability4j.extended.Readability4JExtended
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Reader mode" for a web page: keeps the main article (its text and pictures, in order)
 * and drops menus, ads, side panels, comments and footers.
 *
 * The hard part is done by Readability4J, a port of Mozilla's Readability (the engine
 * behind Firefox's Reader View). This class prepares lazy-loaded images first, then turns
 * the cleaned-up article into text with light formatting markup (the same rules as
 * formatted text on the canvas and in the EPUB builder):
 * "## " / "### " headings, **bold**, *italic*, "> " quotes, "• " and "1. " list items,
 * with literal \* and \\ escaped. It also reads who published the page and when.
 */
object ArticleExtractor {

    sealed interface Part {
        /** One or more paragraphs of formatted text, separated by blank lines. */
        data class Text(val text: String) : Part

        /** An absolute http(s) image URL. */
        data class Image(val url: String) : Part
    }

    /**
     * @param source a formatted "From site · by author · date" line (italic), or null.
     */
    data class Article(val title: String?, val parts: List<Part>, val source: String? = null)

    /** [document] must have its location (base URL) set, as jsoup does when it fetches a page. */
    fun extract(document: Document): Article {
        val pageUrl = document.location()
        // Read the page's own details first: Readability changes the document.
        val siteName = metaContent(document, "og:site_name") ?: hostName(pageUrl)
        val metaAuthor = metaContent(document, "author", "article:author", "parsely-author")
        val published = publishedDate(document)

        useRealImageSources(document, pageUrl)
        val article = Readability4JExtended(pageUrl, document).parse()
        val title = article.title?.trim()?.takeIf { it.isNotEmpty() }
        val author = cleanAuthor(article.byline) ?: cleanAuthor(metaAuthor)
        val source = sourceLine(siteName, author, published)
        val content = article.articleContent ?: return Article(title, emptyList(), source)
        return Article(title, withoutLeadingTitle(toParts(content, pageUrl), title), source)
    }

    // ---- Source line ----

    private fun sourceLine(site: String?, author: String?, date: String?): String? {
        val bits = listOfNotNull(site?.let { "From $it" }, author?.let { "by $it" }, date)
        return if (bits.isEmpty()) null else "*" + escapeMarkup(bits.joinToString(" · ")) + "*"
    }

    private fun metaContent(document: Document, vararg names: String): String? {
        for (name in names) {
            val value = document.selectFirst("meta[property=$name], meta[name=$name], meta[itemprop=$name]")
                ?.attr("content")?.trim()
            if (!value.isNullOrEmpty() && !value.startsWith("http")) return value.take(100)
        }
        return null
    }

    private fun hostName(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }

    private fun cleanAuthor(raw: String?): String? {
        val name = raw?.replace(Regex("\\s+"), " ")?.trim()?.replace(Regex("^(?i)by\\s+"), "")?.trim()
        return name?.takeIf { it.isNotEmpty() && it.length <= 100 && !it.startsWith("http") }
    }

    /** The publication date from the usual meta tags (or a <time> element), as e.g. "8 Nov 2026". */
    private fun publishedDate(document: Document): String? {
        val candidates = listOf(
            "meta[property=article:published_time]", "meta[property=og:published_time]",
            "meta[itemprop=datePublished]", "meta[name=date]", "meta[name=pubdate]",
            "meta[name=publishdate]", "meta[name=publish-date]", "meta[name=dc.date]",
            "meta[name=dc.date.issued]", "meta[name=article.published]",
        ).mapNotNull { document.selectFirst(it)?.attr("content") } +
            listOfNotNull(
                document.selectFirst("[itemprop=datePublished][datetime]")?.attr("datetime"),
                document.selectFirst("time[datetime]")?.attr("datetime"),
            )
        for (value in candidates) {
            val m = ISO_DATE.find(value) ?: continue
            val date = runCatching {
                LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }.getOrNull() ?: continue
            return date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
        }
        return null
    }

    // ---- Article text ----

    /** The book already shows the title as its heading, so drop it if the article repeats it first. */
    private fun withoutLeadingTitle(parts: List<Part>, title: String?): List<Part> {
        val first = parts.firstOrNull() as? Part.Text ?: return parts
        if (title == null) return parts
        val paragraphs = first.text.split("\n\n")
        if (!plain(paragraphs.first()).equals(title.trim(), ignoreCase = true)) return parts
        val rest = paragraphs.drop(1).joinToString("\n\n")
        return if (rest.isBlank()) parts.drop(1) else listOf(Part.Text(rest)) + parts.drop(1)
    }

    /** A paragraph without its markup, to compare with plain text. */
    private fun plain(paragraph: String) = paragraph.trim()
        .replace(Regex("^#{1,6}\\s+"), "")
        .replace("\\\\", "\uE000").replace("\\*", "\uE001").replace("*", "")
        .replace("\uE000", "\\").replace("\uE001", "*")
        .trim()

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

    /** Walks the article in document order, turning it into formatted text runs and images. */
    private fun toParts(root: Element, pageUrl: String): List<Part> {
        val parts = mutableListOf<Part>()
        val paragraphs = mutableListOf<String>()
        val line = StringBuilder()
        var paragraphNumber = 0 // changes whenever a paragraph ends, so inline markers know if they still apply
        var pendingPrefix = ""
        var quoteDepth = 0
        val lists = ArrayDeque<IntArray>() // per open list: [0] = 1 for numbered, [1] = items so far
        val inlineStarts = ArrayDeque<IntArray>() // per open bold/italic: [0] = paragraphNumber, [1] = position in line

        fun endParagraph() {
            val text = line.lines().joinToString("\n") { it.replace(SPACES, " ").trim() }.trim()
            if (text.isNotEmpty()) paragraphs += text
            line.setLength(0)
            paragraphNumber++
        }

        fun endTextRun() {
            endParagraph()
            if (paragraphs.isNotEmpty()) parts += Part.Text(paragraphs.joinToString("\n\n"))
            paragraphs.clear()
        }

        fun appendText(text: String) {
            if (line.isBlank() && text.isNotBlank()) {
                line.setLength(0)
                line.append(if (quoteDepth > 0) "> " else "").append(pendingPrefix)
                pendingPrefix = ""
                // Bold/italic that opened before this text starts after the prefix, not around it.
                for (open in inlineStarts) if (open[0] == paragraphNumber) open[1] = line.length
            }
            line.append(text)
        }

        /** Wraps what was added since the element started in [marker], keeping spaces outside it. */
        fun wrapInline(marker: String) {
            val (paragraph, start) = inlineStarts.removeLastOrNull() ?: return
            if (paragraph != paragraphNumber || start > line.length) return
            val inner = line.substring(start)
            val core = inner.trim()
            if (core.isEmpty()) return
            val lead = inner.takeWhile { it.isWhitespace() }
            val trail = inner.takeLastWhile { it.isWhitespace() }
            line.setLength(start)
            line.append(lead).append(marker).append(core).append(marker).append(trail)
        }

        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is TextNode) {
                    val text = if (isInsidePre(node)) node.wholeText else node.text()
                    appendText(escapeMarkup(text))
                    return
                }
                if (node !is Element) return
                when (val tag = node.normalName()) {
                    "img" -> resolve(pageUrl, node.attr("src"))?.let {
                        endTextRun()
                        parts += Part.Image(it)
                    }
                    "br" -> line.append('\n')
                    "strong", "b", "em", "i" -> inlineStarts.addLast(intArrayOf(paragraphNumber, line.length))
                    else -> if (node.isBlock) {
                        endParagraph()
                        when (tag) {
                            "h1", "h2" -> pendingPrefix = "## "
                            "h3", "h4", "h5", "h6" -> pendingPrefix = "### "
                            "blockquote" -> quoteDepth++
                            "ul" -> lists.addLast(intArrayOf(0, 0))
                            "ol" -> lists.addLast(intArrayOf(1, 0))
                            "li" -> {
                                val list = lists.lastOrNull()
                                pendingPrefix = if (list != null && list[0] == 1) "${++list[1]}. " else "• "
                            }
                        }
                    }
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node !is Element) return
                when (node.normalName()) {
                    "strong", "b" -> wrapInline("**")
                    "em", "i" -> wrapInline("*")
                    else -> if (node.isBlock) {
                        endParagraph()
                        pendingPrefix = ""
                        when (node.normalName()) {
                            "blockquote" -> quoteDepth = (quoteDepth - 1).coerceAtLeast(0)
                            "ul", "ol" -> lists.removeLastOrNull()
                        }
                    }
                }
            }
        }, root)
        endTextRun()
        return parts
    }

    /** Text that should appear literally: protect the characters the markup uses. */
    private fun escapeMarkup(text: String) = text.replace("\\", "\\\\").replace("*", "\\*")

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
    private val ISO_DATE = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
}
