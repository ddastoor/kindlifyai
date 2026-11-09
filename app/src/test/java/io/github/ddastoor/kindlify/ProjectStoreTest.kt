package io.github.ddastoor.kindlify

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ddastoor.kindlify.data.Block
import io.github.ddastoor.kindlify.data.Project
import io.github.ddastoor.kindlify.data.ProjectStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Saved canvases: new fields round-trip, and canvases saved by older versions still load. */
@RunWith(AndroidJUnit4::class)
class ProjectStoreTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val store get() = ProjectStore(app.filesDir)

    @Test
    fun canvasSavedByVersion1_4_loadsUnchanged() {
        // Exactly what version 1.4 writes (no "formatted", no separator "title").
        val dir = store.projectDir("old").apply { mkdirs() }
        File(dir, "project.json").writeText(
            """{"id":"old","title":"Old canvas","createdAt":1,"updatedAt":2,"epubFileName":"Old canvas.epub","epubBuiltFrom":2,
               "blocks":[{"id":"t","type":"text","text":"## plain, not a heading"},
                         {"id":"s","type":"separator"},
                         {"id":"i","type":"image","file":"x.jpg","width":10,"height":20}]}""",
        )
        val project = store.load("old")!!
        assertEquals(
            listOf(
                Block.Text("t", "## plain, not a heading", formatted = false),
                Block.Separator("s", title = null),
                Block.Image("i", "x.jpg", 10, 20),
            ),
            project.blocks,
        )
        assertEquals(true, project.hasCurrentEpub)
    }

    @Test
    fun formattedTextAndChapterTitlesRoundTrip() {
        val project = Project(
            "new", "New canvas", 1, 2,
            listOf(
                Block.Text("a", "plain"),
                Block.Separator("s1", "A Walk Through the Old Harbour"),
                Block.Text("b", "## Heading\n\n**bold**", formatted = true),
                Block.Separator("s2"),
            ),
        )
        store.save(project)
        assertEquals(project, store.load("new"))
        // Plain text and untitled separators are written exactly as before.
        val json = File(store.projectDir("new"), "project.json").readText()
        assertEquals(1, Regex("\"formatted\"").findAll(json).count())
        assertEquals(1, Regex("\"title\":\"A Walk").findAll(json).count())
    }
}
