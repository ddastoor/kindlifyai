package io.github.ddastoor.kindlify

import android.app.Application
import android.content.ClipData
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ddastoor.kindlify.data.Block
import io.github.ddastoor.kindlify.data.ProjectStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Sharing from another app (Android share sheet): with no canvas open it starts a new
 * canvas; with a canvas open it's appended to it, after a separator line.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ShareToAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val store get() = ProjectStore(app.filesDir)

    @Before
    fun setUp() = resetFileProviderCache()

    @Test
    fun sharedText_withNoCanvasOpen_startsNewCanvas_titledFromSubject() {
        compose.onNodeWithText("No canvases yet").assertIsDisplayed()

        share(textShare("A paragraph someone shared from their browser.", subject = "A great article"))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("A paragraph someone shared", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("A great article").assertIsDisplayed()
        compose.screenshot("07-shared-text-new-canvas")

        val project = store.list().single()
        assertEquals("A great article", project.title)
        assertEquals(1, project.blocks.size)
    }

    @Test
    fun sharedText_withCanvasOpen_isAppendedAfterSeparator() {
        compose.onNodeWithText("New canvas").performClick()
        compose.onNodeWithText("Your canvas is empty").assertIsDisplayed()

        // Open but empty canvas: no separator needed in front of the first item.
        share(textShare("First shared paragraph."))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("First shared paragraph.")).fetchSemanticsNodes().isNotEmpty() }

        // Canvas now has content: the next share goes after a separator line, which starts a
        // new chapter named after the share's subject (the canvas keeps its own title).
        share(textShare("Second shared paragraph.", subject = "The second article"))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Second shared paragraph.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Separator line").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Added to the end of this canvas.")).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("08-shared-text-appended")

        val project = store.list().single()
        assertTrue("title kept: ${project.title}", project.title.startsWith("Untitled"))
        assertEquals(3, project.blocks.size)
        assertEquals("First shared paragraph.", (project.blocks[0] as Block.Text).text)
        assertEquals("The second article", (project.blocks[1] as Block.Separator).title)
        assertEquals("Second shared paragraph.", (project.blocks[2] as Block.Text).text)
    }

    @Test
    fun sharedImages_withNoCanvasOpen_startsNewCanvas() {
        val first = File(app.filesDir, "projects/share-source/one.jpg").also { writeTestPhoto(it, 800, 600) }
        val second = File(app.filesDir, "projects/share-source/two.jpg").also { writeTestPhoto(it, 600, 900) }
        val uris = listOf(first, second).map { FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", it) }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("image/jpeg")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("", uris[0]).apply { addItem(ClipData.Item(uris[1])) }

        share(intent)
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Pasted image")).fetchSemanticsNodes().size == 2 }

        val project = store.list().single()
        assertEquals(2, project.blocks.size)
        assertTrue(project.blocks.all { it is Block.Image })
        assertTrue(project.title.startsWith("Untitled"))
    }

    private fun textShare(text: String, subject: String? = null) =
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).apply {
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
        }

    /** Delivers a share to the running app, as Android does for a singleTask activity. */
    private fun share(intent: Intent) {
        intent.setClass(app, MainActivity::class.java)
        compose.runOnUiThread { compose.activity.onNewIntent(intent) }
    }
}
