package io.github.ddastoor.kindlify

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xmlpull.v1.XmlPullParser

/**
 * The launcher shortcuts: "Paste into a canvas" (the same choices as sharing) and
 * "New canvas from clipboard".
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ShortcutTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val clipboard get() = app.getSystemService(ClipboardManager::class.java)
    private val store get() = ProjectStore(app.filesDir)

    @Before
    fun setUp() = resetFileProviderCache()

    @Test
    fun newCanvasFromClipboard() {
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "Copied before tapping the shortcut."))
        shortcut(MainActivity.ACTION_PASTE_INTO_NEW)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Copied before tapping the shortcut.")).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("13-shortcut-new-canvas")
        val project = store.list().single()
        assertEquals(listOf("Copied before tapping the shortcut."), project.blocks.map { (it as Block.Text).text })
    }

    @Test
    fun pasteIntoCanvas_asksWhichLikeSharing_andAppendsAfterSeparator() {
        store.save(Project("older", "Older canvas", 1, 1_000, listOf(Block.Text("o", "Older note"))))
        store.save(Project("newer", "Newer canvas", 2, 2_000, listOf(Block.Text("n", "Newer note"))))
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "Added by the shortcut."))

        shortcut(MainActivity.ACTION_PASTE_INTO_CANVAS)
        compose.waitForCanvasPicker()
        compose.onNodeWithText("The copied text goes at the end of the canvas you pick, after a separator, or into a new canvas.").assertIsDisplayed()
        compose.pickerNewCanvas().assertIsDisplayed()
        compose.onNodeWithText("Older canvas").assertIsDisplayed()
        compose.screenshot("15-shortcut-pick-canvas")
        compose.onNodeWithText("Newer canvas").performClick()

        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Added by the shortcut.")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { store.load("newer")!!.blocks.size == 3 }
        val newer = store.load("newer")!!
        assertEquals("Newer note", (newer.blocks[0] as Block.Text).text)
        assertTrue("separator before the pasted content, as with sharing", newer.blocks[1] is Block.Separator)
        assertEquals("Added by the shortcut.", (newer.blocks[2] as Block.Text).text)
        assertEquals(listOf("Older note"), store.load("older")!!.blocks.map { (it as Block.Text).text })
        assertEquals(2, store.list().size)
    }

    @Test
    fun pasteIntoCanvas_newCanvasInTheList() {
        store.save(Project("only", "Only canvas", 1, 1_000, listOf(Block.Text("o", "Only note"))))
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "Into a fresh canvas."))

        shortcut(MainActivity.ACTION_PASTE_INTO_CANVAS)
        compose.waitForCanvasPicker()
        compose.pickerNewCanvas().performClick()

        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Into a fresh canvas.")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { store.list().size == 2 }
        val fresh = store.list().single { it.id != "only" }
        assertEquals(listOf("Into a fresh canvas."), fresh.blocks.map { (it as Block.Text).text })
        assertEquals(listOf("Only note"), store.load("only")!!.blocks.map { (it as Block.Text).text })
    }

    @Test
    fun pasteIntoCanvas_cancelAddsNothing() {
        store.save(Project("only", "Only canvas", 1, 1_000, listOf(Block.Text("o", "Only note"))))
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "Not wanted."))

        shortcut(MainActivity.ACTION_PASTE_INTO_CANVAS)
        compose.waitForCanvasPicker()
        compose.onNodeWithText("Cancel").performClick()

        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Paste cancelled. Nothing was added.")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, store.list().size)
        assertEquals(listOf("Only note"), store.load("only")!!.blocks.map { (it as Block.Text).text })
    }

    @Test
    fun pasteIntoCanvas_withNoCanvases_startsOne() {
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "First ever note."))
        shortcut(MainActivity.ACTION_PASTE_INTO_CANVAS)
        // Wait on the screen (which also lets the app's main thread run), then check what was saved.
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("First ever note.")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { store.list().singleOrNull()?.blocks?.isNotEmpty() == true }
        assertEquals("First ever note.", (store.list().single().blocks.single() as Block.Text).text)
    }

    @Test
    fun emptyClipboard_saysSo_andCreatesNothing() {
        clipboard.clearPrimaryClip()
        shortcut(MainActivity.ACTION_PASTE_INTO_NEW)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("clipboard is empty", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun coldStart_waitsForWindowFocusBeforeReadingTheClipboard() {
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "Cold start paste."))
        val intent = Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_PASTE_INTO_NEW)
        Robolectric.buildActivity(MainActivity::class.java, intent).use { controller ->
            controller.create().start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            // Each shortcut test runs in a fresh app, so only this activity's work shows up here.
            assertTrue("nothing happens before the window has focus", ProjectStore(app.filesDir).list().none { p -> p.blocks.isNotEmpty() })

            controller.visible()
            controller.windowFocusChanged(true)
            val deadline = System.currentTimeMillis() + 5_000
            while (ProjectStore(app.filesDir).list().none { p -> p.blocks.isNotEmpty() } && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            val pasted = ProjectStore(app.filesDir).list().single { p -> p.blocks.isNotEmpty() }
            assertEquals("Cold start paste.", (pasted.blocks.single() as Block.Text).text)
            assertTrue(controller.get().intent.getBooleanExtra(MainActivity.EXTRA_SHARE_HANDLED, false))
        }
    }

    @Test
    fun shortcutsFile_pointsAtTheAppWithTheRightActions() {
        val parser = app.resources.getXml(R.xml.shortcuts)
        val intents = mutableListOf<Pair<String, String>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "intent") {
                val ns = "http://schemas.android.com/apk/res/android"
                assertEquals(app.packageName, parser.getAttributeValue(ns, "targetPackage"))
                intents += parser.getAttributeValue(ns, "action") to parser.getAttributeValue(ns, "targetClass")
            }
        }
        assertEquals(
            listOf(
                MainActivity.ACTION_PASTE_INTO_CANVAS to MainActivity::class.java.name,
                MainActivity.ACTION_PASTE_INTO_NEW to MainActivity::class.java.name,
            ),
            intents,
        )
    }

    /** Delivers a launcher shortcut to the running app and gives its window focus. */
    private fun shortcut(action: String) {
        val intent = Intent(app, MainActivity::class.java).setAction(action)
        compose.runOnUiThread {
            compose.activity.onNewIntent(intent)
            compose.activity.onWindowFocusChanged(true)
        }
    }
}
