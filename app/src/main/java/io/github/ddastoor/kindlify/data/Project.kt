package io.github.ddastoor.kindlify.data

/** A canvas: a titled, ordered list of pasted text and images. */
data class Project(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val blocks: List<Block>,
    /** Name of the last EPUB built from this canvas, if any. */
    val epubFileName: String? = null,
    /** The [updatedAt] the EPUB was built from; if the canvas changed since, the EPUB is stale. */
    val epubBuiltFrom: Long = 0,
) {
    val hasCurrentEpub: Boolean get() = epubFileName != null && epubBuiltFrom == updatedAt
}

/**
 * The web address if the canvas holds nothing but a single link (separators and empty
 * text aside), which is when "Fetch contents" is available; otherwise null.
 * A bare "example.com/page" gets https:// added.
 */
fun Project.soleLink(): String? {
    val content = blocks.filterNot { it is Block.Separator || (it is Block.Text && it.text.isBlank()) }
    val text = (content.singleOrNull() as? Block.Text)?.text?.trim() ?: return null
    if (text.any { it.isWhitespace() }) return null
    return when {
        LINK_WITH_SCHEME.matches(text) -> text
        BARE_DOMAIN_LINK.matches(text) -> "https://$text"
        else -> null
    }
}

private val LINK_WITH_SCHEME = Regex("(?i)https?://[^\\s/?#]+\\S*")
private val BARE_DOMAIN_LINK = Regex("(?i)([a-z0-9-]+\\.)+[a-z]{2,}(:\\d+)?([/?#]\\S*)?")

sealed interface Block {
    val id: String

    /**
     * Text. Pasted and shared text is plain ([formatted] = false) and goes into the book exactly as
     * typed. Text fetched from web pages is [formatted]: it uses a small markup that becomes real
     * formatting in the book: lines starting "## " / "### " are headings, "> " quotes, "• " and
     * "1. " list items, and **bold** / *italic* inline (a backslash keeps a literal \* or \\).
     */
    data class Text(override val id: String, val text: String, val formatted: Boolean = false) : Block

    /** An image stored (as JPEG or PNG) in the project's images folder. */
    data class Image(override val id: String, val fileName: String, val width: Int, val height: Int) : Block

    /**
     * A dividing line, which starts a new chapter in the book. [title] names that chapter in the
     * Kindle's table of contents (a fetched article's title, or a shared page's subject); without
     * one, the chapter is named after its first line.
     */
    data class Separator(override val id: String, val title: String? = null) : Block
}
