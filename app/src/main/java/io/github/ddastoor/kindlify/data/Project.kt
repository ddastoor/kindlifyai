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

sealed interface Block {
    val id: String

    data class Text(override val id: String, val text: String) : Block

    /** An image stored (as JPEG or PNG) in the project's images folder. */
    data class Image(override val id: String, val fileName: String, val width: Int, val height: Int) : Block
}
