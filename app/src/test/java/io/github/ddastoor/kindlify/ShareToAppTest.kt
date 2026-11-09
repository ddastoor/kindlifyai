package io.github.ddastoor.kindlify

import android.app.Application
import android.content.ClipData
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ddastoor.kindlify.data.Block
import io.github.ddastoor.kindlify.data.Project
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
 * Sharing from another app (Android share sheet): with no canvases it starts a new one;
 * otherwise you pick a canvas from a list (it's appended after a separator), pick
 * "New canvas", or Cancel.
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
    fun sharedText_withOneCanvasOpen_asks_thenAppendsAfterSeparator() {
        compose.onNodeWithText("New canvas").performClick()
        compose.onNodeWithText("Your canvas is empty").assertIsDisplayed()

        // Even with a single canvas, the list is shown (with "New canvas"); pick the open one.
        // It's still empty, so no separator is needed in front of the first item.
        share(textShare("First shared paragraph."))
        waitForPicker()
        pickerNewCanvas().assertIsDisplayed()
        compose.onNodeWithText("open now", substring = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("First shared paragraph.")).fetchSemanticsNodes().isNotEmpty() }

        // Canvas now has content: the next share goes after a separator line, which starts a
        // new chapter named after the share's subject (the canvas keeps its own title).
        share(textShare("Second shared paragraph.", subject = "The second article"))
        waitForPicker()
        compose.onNodeWithText("open now", substring = true).performClick()
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

    @Test
    fun sharedText_withOneSavedCanvas_asks_thenOpensItAndAppends() {
        store.save(Project("only", "My only canvas", 1, 1_000, listOf(Block.Text("t", "Existing note"))))

        share(textShare("Shared into the only canvas.", subject = "Some page"))
        waitForPicker()
        pickerNewCanvas().assertIsDisplayed()
        compose.onNodeWithText("My only canvas").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Shared into the only canvas.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("My only canvas").assertIsDisplayed()

        compose.waitUntil(5_000) { store.load("only")!!.blocks.size == 3 }
        val project = store.load("only")!!
        assertEquals("Existing note", (project.blocks[0] as Block.Text).text)
        assertEquals("Some page", (project.blocks[1] as Block.Separator).title)
        assertEquals("Shared into the only canvas.", (project.blocks[2] as Block.Text).text)
        assertEquals("no new canvas", 1, store.list().size)
    }

    @Test
    fun sharedText_withSeveralCanvases_asksWhich_andAddsToThePickedOne() {
        seedThreeCanvases()

        share(textShare("Goes where I choose.", subject = "Chosen article"))
        waitForPicker()
        for (title in listOf("Canvas A", "Canvas B", "Canvas C")) compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText("“Chosen article” goes at the end of the canvas you pick, after a separator, or into a new canvas.").assertIsDisplayed()
        pickerNewCanvas().assertIsDisplayed()
        compose.screenshot("14-share-pick-canvas")

        compose.onNodeWithText("Canvas B").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Goes where I choose.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add to which canvas?").assertDoesNotExist()

        compose.waitUntil(5_000) { store.load("b")!!.blocks.size == 3 }
        assertEquals(listOf("Note A"), store.load("a")!!.blocks.map { (it as Block.Text).text })
        assertEquals(listOf("Note C"), store.load("c")!!.blocks.map { (it as Block.Text).text })
        val b = store.load("b")!!
        assertEquals("Chosen article", (b.blocks[1] as Block.Separator).title)
        assertEquals("Goes where I choose.", (b.blocks[2] as Block.Text).text)
        assertEquals(3, store.list().size)
    }

    @Test
    fun sharedText_withSeveralCanvases_cancelAddsNothing() {
        seedThreeCanvases()

        share(textShare("Never mind."))
        waitForPicker()
        compose.onNodeWithText("The shared 1 text goes at the end of the canvas you pick, after a separator, or into a new canvas.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()

        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Share cancelled. Nothing was added.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add to which canvas?").assertDoesNotExist()
        compose.onNodeWithText("Never mind.").assertDoesNotExist()
        assertEquals(3, store.list().size)
        assertTrue(store.list().all { it.blocks.size == 1 })
    }

    @Test
    fun sharedText_withACanvasOpenAndOthers_stillAsks_andKeepsTheOpenCanvasEdits() {
        seedThreeCanvases()
        // Open canvas A from the list and edit it, then share while it's open.
        compose.onNodeWithText("New canvas").performClick()
        compose.onNodeWithContentDescription("Back to all canvases").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Canvas A")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Canvas A").performClick()
        compose.onNodeWithText("Note A").performTextInput("Edited ")

        share(textShare("Into C please."))
        waitForPicker()
        compose.onNodeWithText("open now", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Canvas C").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Into C please.")).fetchSemanticsNodes().isNotEmpty() }

        compose.waitUntil(5_000) { store.load("c")!!.blocks.size == 3 }
        compose.waitUntil(5_000) { (store.load("a")!!.blocks.single() as Block.Text).text == "Edited Note A" }
    }

    @Test
    fun newCanvasInTheList_startsACanvasWithTheShare() {
        seedThreeCanvases()

        share(textShare("Fresh start.", subject = "Fresh article"))
        waitForPicker()
        compose.onNodeWithText("Start a fresh canvas with it").assertIsDisplayed()
        pickerNewCanvas().performClick()

        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Fresh start.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add to which canvas?").assertDoesNotExist()
        compose.onNodeWithText("Fresh article").assertIsDisplayed()
        compose.waitUntil(5_000) { store.list().size == 4 }
        val fresh = store.list().single { it.title == "Fresh article" }
        assertEquals("no separator in a new canvas", listOf("Fresh start."), fresh.blocks.map { (it as Block.Text).text })
        for (id in listOf("a", "b", "c")) assertEquals(1, store.load(id)!!.blocks.size)
    }

    @Test
    fun newCanvasInTheList_atTheLimit_saysWhichCanvasGoes() {
        for (i in 1..AppViewModel.MAX_CANVASES) {
            store.save(Project("canvas-$i", "Canvas $i", i.toLong(), i * 60_000L, listOf(Block.Text("t$i", "Note $i"))))
        }

        share(textShare("One too many."))
        waitForPicker()
        compose.onNodeWithText("Removes your oldest canvas, “Canvas 1” (20 are kept)").assertIsDisplayed()
        pickerNewCanvas().performClick()

        compose.waitUntil(5_000) { compose.onAllNodes(hasText("One too many.")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { store.load("canvas-1") == null }
        assertEquals(AppViewModel.MAX_CANVASES, store.list().size)
    }

    /** The "New canvas" entry in the picker (the home screen has a "New canvas" button too). */
    private fun pickerNewCanvas() = compose.onNode(hasText("New canvas") and hasAnyAncestor(isDialog()))

    /** The picker appears once the app has looked at the saved canvases (a background disk read). */
    private fun waitForPicker() {
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Add to which canvas?").assertIsDisplayed() }.isSuccess
        }
    }

    private fun seedThreeCanvases() {
        store.save(Project("a", "Canvas A", 1, 3_000, listOf(Block.Text("ta", "Note A"))))
        store.save(Project("b", "Canvas B", 2, 2_000, listOf(Block.Text("tb", "Note B"))))
        store.save(Project("c", "Canvas C", 3, 1_000, listOf(Block.Text("tc", "Note C"))))
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
