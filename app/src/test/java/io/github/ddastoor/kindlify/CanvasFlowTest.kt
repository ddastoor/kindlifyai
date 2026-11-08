package io.github.ddastoor.kindlify

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ddastoor.kindlify.data.Block
import io.github.ddastoor.kindlify.data.ProjectStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class CanvasFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = resetFileProviderCache()
    private val clipboard get() = app.getSystemService(ClipboardManager::class.java)
    private val store get() = ProjectStore(app.filesDir)

    @Test
    fun pasteTextAndImage_createEpub_exportToKindle_thenCancel() {
        compose.onNodeWithText("No canvases yet").assertIsDisplayed()
        compose.screenshot("01-home-empty")

        compose.onNodeWithText("New canvas").performClick()
        compose.onNodeWithText("Your canvas is empty").assertIsDisplayed()
        compose.onNodeWithText("Create EPUB").assertIsNotEnabled()
        compose.onNodeWithText("Export").assertIsNotEnabled()
        compose.screenshot("02-canvas-empty")

        // Paste text from the clipboard.
        val article = "It was a bright cold day in April, and the clocks were striking thirteen.\n\n" +
            "Second paragraph, with an ampersand & some <angle brackets>."
        clipboard.setPrimaryClip(ClipData.newPlainText("text", article))
        compose.onNodeWithText("Paste").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("bright cold day", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        // Paste an image from the clipboard (shared through a content:// URI, as other apps do).
        val photo = File(app.filesDir, "projects/clipboard-source/photo.jpg").also { writeTestPhoto(it) }
        val photoUri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", photo)
        clipboard.setPrimaryClip(ClipData.newUri(app.contentResolver, "photo", photoUri))
        compose.onNodeWithText("Paste").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescriptionPastedImage()).fetchSemanticsNodes().isNotEmpty() }

        // And a second text block after it.
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "A closing note after the picture."))
        compose.onNodeWithText("Paste").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("closing note", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.screenshot("03-canvas-with-content")

        val project = store.list().single()
        assertEquals(3, project.blocks.size)
        val image = project.blocks[1] as Block.Image
        assertEquals("downscaled to 2000px long side", 2000, image.width)
        assertEquals(1333, image.height)

        // Create the EPUB: first asks for the title, pre-filled with "Untitled <date>".
        compose.onNodeWithText("Create EPUB").performClick()
        compose.onNodeWithText("Book title").assertTextContains("Untitled", substring = true)
        compose.screenshot("04a-epub-title-dialog")
        compose.onNodeWithText("Book title").performTextReplacement("Weekend reading")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("EPUB ready.", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Export").assertIsEnabled()
        compose.screenshot("04-epub-ready")

        val built = store.list().single()
        assertEquals("Weekend reading", built.title)
        val epub = store.epubFile(built)
        assertNotNull(epub)
        assertEquals("Weekend reading.epub", epub!!.name)
        ZipFile(epub).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals("mimetype", names.first())
            assertTrue(names.toString(), names.any { it.endsWith("images/image001.jpg") })
            val page = zip.getInputStream(zip.getEntry("OEBPS/content.xhtml")).reader().readText()
            assertTrue(page, page.contains("<p>Second paragraph, with an ampersand &amp; some &lt;angle brackets&gt;.</p>"))
            val opf = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).reader().readText()
            assertTrue(opf, opf.contains("<dc:title>Weekend reading</dc:title>"))
        }
        // Keep a copy so it can be run through epubcheck after the test.
        epub.copyTo(File("build/e2e/canvas-flow.epub").apply { parentFile!!.mkdirs() }, overwrite = true)

        // Export: with the Kindle app installed, the EPUB goes straight to it.
        installFakeKindleApp()
        compose.onNodeWithText("Export").performClick()
        val sent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(KindleShare.KINDLE_PACKAGE, sent.`package`)
        assertEquals(Intent.ACTION_SEND, sent.action)
        assertEquals(KindleShare.EPUB_MIME, sent.type)
        val stream = IntentCompat.getParcelableExtra(sent, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("${app.packageName}.fileprovider", stream.authority)
        assertTrue(stream.toString().endsWith(".epub"))
        assertTrue(sent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(epub.length(), app.contentResolver.openInputStream(stream)!!.use { it.readBytes().size.toLong() })

        // Editing the canvas makes the EPUB stale, so Export is disabled until it is rebuilt.
        compose.onNodeWithText("A closing note after the picture.").performTextInput("PS: ")
        compose.onNodeWithText("Export").assertIsNotEnabled()

        // Back to the list: the canvas is kept as a draft.
        compose.onNodeWithContentDescription("Back to all canvases").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("2 texts", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("05-home-with-draft")

        // Reopen it and abandon it with Cancel.
        compose.onNodeWithText("Weekend reading").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Discard this canvas?").assertIsDisplayed()
        compose.screenshot("06-cancel-dialog")
        compose.onNodeWithText("Discard").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("No canvases yet")).fetchSemanticsNodes().isNotEmpty() }
        assertFalse("canvas files deleted", store.projectDir(project.id).exists())
    }

    @Test
    fun export_withoutKindleApp_fallsBackToShareSheet() {
        compose.onNodeWithText("New canvas").performClick()
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "Just some text."))
        compose.onNodeWithText("Paste").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Just some text.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Create EPUB").performClick()
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("EPUB ready.", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Export").performClick()
        val sent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, sent.action)
        val target = IntentCompat.getParcelableExtra(sent, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(KindleShare.EPUB_MIME, target.type)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Kindle app not found", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun paste_withEmptyClipboard_explainsWhatToDo() {
        compose.onNodeWithText("New canvas").performClick()
        clipboard.clearPrimaryClip()
        compose.onNodeWithText("Paste").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("clipboard is empty", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun hasContentDescriptionPastedImage() = androidx.compose.ui.test.hasContentDescription("Pasted image")

    private fun installFakeKindleApp() {
        val pm = shadowOf(app.packageManager)
        val component = ComponentName(KindleShare.KINDLE_PACKAGE, "com.amazon.kindle.SendToKindleActivity")
        pm.installPackage(PackageInfo().apply {
            packageName = KindleShare.KINDLE_PACKAGE
            applicationInfo = ApplicationInfo().apply { packageName = KindleShare.KINDLE_PACKAGE }
        })
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_SEND).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataType(KindleShare.EPUB_MIME)
        })
    }
}
