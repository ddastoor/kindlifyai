package io.github.ddastoor.kindlify

import io.github.ddastoor.kindlify.data.Block
import io.github.ddastoor.kindlify.data.Project
import io.github.ddastoor.kindlify.data.soleLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Fetch contents" is only available when the canvas holds nothing but a link. */
class SoleLinkTest {

    private var n = 0
    private fun text(t: String) = Block.Text("t${n++}", t)
    private fun canvas(vararg blocks: Block) = Project("p", "Untitled", 0, 0, blocks.toList())

    @Test
    fun aSingleLinkIsFetchable() {
        assertEquals("https://example.com/a?b=1#c", canvas(text("  https://example.com/a?b=1#c \n")).soleLink())
        assertEquals("http://127.0.0.1:8080/story.html", canvas(text("http://127.0.0.1:8080/story.html")).soleLink())
        assertEquals("https://www.bbc.co.uk/news", canvas(text("www.bbc.co.uk/news")).soleLink())
        // Empty text items and separators don't count as content.
        assertEquals("https://example.com", canvas(text(" "), Block.Separator("s"), text("https://example.com")).soleLink())
    }

    @Test
    fun anythingElseIsNot() {
        assertNull(canvas().soleLink())
        assertNull(canvas(text("just some words")).soleLink())
        assertNull(canvas(text("read this: https://example.com")).soleLink())
        assertNull(canvas(text("https://example.com"), text("and a note")).soleLink())
        assertNull(canvas(text("https://example.com"), Block.Image("i", "x.jpg", 10, 10)).soleLink())
        assertNull(canvas(text("ftp://example.com/file")).soleLink())
        assertNull(canvas(text("not.a.link.")).soleLink())
    }
}
