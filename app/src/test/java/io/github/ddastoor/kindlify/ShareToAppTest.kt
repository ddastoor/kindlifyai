package io.github.ddastoor.kindlify

import android.app.Application
import android.content.ClipData
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/** Sharing from another app (Android share sheet) starts a new canvas with the shared content. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ShareToAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = resetFileProviderCache()
    private val store get() = ProjectStore(app.filesDir)

    @Test
    fun sharedText_opensNewCanvas_titledFromSubject_keepingTheOpenOne() {
        // You are in the middle of another canvas when you share something.
        compose.onNodeWithText("New canvas").performClick()
        compose.onNodeWithText("Your canvas is empty").assertIsDisplayed()

        share(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "A great article")
                .putExtra(Intent.EXTRA_TEXT, "A paragraph someone shared from their browser."),
        )
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("A paragraph someone shared", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("A great article").assertIsDisplayed()
        compose.screenshot("07-shared-text")

        compose.waitUntil(5_000) { store.list().size == 2 }
        val projects = store.list()
        val shared = projects.single { it.title == "A great article" }
        assertEquals(1, shared.blocks.size)
        assertTrue("earlier canvas kept as a draft", projects.any { it.title.startsWith("Untitled") })
    }

    @Test
    fun sharedImages_opensNewCanvas() {
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

    /** Delivers a share to the running app, as Android does for a singleTask activity. */
    private fun share(intent: Intent) {
        intent.setClass(app, MainActivity::class.java)
        compose.runOnUiThread { compose.activity.onNewIntent(intent) }
    }
}
