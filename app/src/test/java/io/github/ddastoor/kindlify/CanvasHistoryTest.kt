package io.github.ddastoor.kindlify

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ddastoor.kindlify.data.Block
import io.github.ddastoor.kindlify.data.Project
import io.github.ddastoor.kindlify.data.ProjectStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Up to 20 canvases are kept; starting a 21st removes the least recently edited one. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class CanvasHistoryTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val store get() = ProjectStore(app.filesDir)

    @Before
    fun setUp() = resetFileProviderCache()

    @Test
    fun creatingThe21stCanvas_removesTheLeastRecentlyEdited() {
        // 20 saved canvases; "Canvas 1" was edited longest ago, "Canvas 20" most recently.
        for (i in 1..20) {
            store.save(Project("canvas-$i", "Canvas $i", createdAt = i * 1_000L, updatedAt = i * 60_000L, blocks = listOf(Block.Text("b$i", "Note $i"))))
            File(store.imagesDir("canvas-$i").apply { mkdirs() }, "pic.jpg").writeText("x")
        }
        assertEquals(AppViewModel.MAX_CANVASES, store.list().size)

        compose.onNodeWithText("New canvas").performClick()
        compose.waitUntil(5_000) { store.load("canvas-1") == null }

        assertEquals(AppViewModel.MAX_CANVASES, store.list().size)
        assertFalse("its folder (images, EPUB) is gone", store.projectDir("canvas-1").exists())
        assertNotNull(store.load("canvas-2"))
        assertNotNull(store.load("canvas-20"))

        // The home screen lists the 20 that are kept, and says so.
        compose.onNodeWithContentDescription("Back to all canvases").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("most recently edited canvases are kept", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("10-home-history")
        compose.onNodeWithText("Canvas 20").performClick()
        compose.onNodeWithText("Note 20").assertExists()
    }

    @Test
    fun longPressDeletesOneCanvas_andDeleteAllDeletesEverything() {
        for (i in 1..3) {
            store.save(Project("canvas-$i", "Canvas $i", createdAt = i * 1_000L, updatedAt = i * 60_000L, blocks = listOf(Block.Text("b$i", "Note $i"))))
        }
        // The list was read when the app started; opening and leaving a canvas re-reads it.
        compose.onNodeWithText("New canvas").performClick()
        compose.onNodeWithContentDescription("Back to all canvases").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Canvas 2")).fetchSemanticsNodes().isNotEmpty() }

        // Long-press one canvas: asks first, then deletes just that one.
        compose.onNodeWithText("Canvas 2").performTouchInput { longClick() }
        compose.onNodeWithText("Delete this canvas?").assertIsDisplayed()
        compose.screenshot("11-delete-one-dialog")
        compose.onNodeWithText("Keep").performClick()
        assertNotNull("Keep leaves it alone", store.load("canvas-2"))

        compose.onNodeWithText("Canvas 2").performTouchInput { longClick() }
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { store.load("canvas-2") == null }
        compose.onNodeWithText("Canvas 2").assertDoesNotExist()
        assertNotNull(store.load("canvas-1"))
        assertNotNull(store.load("canvas-3"))
        assertEquals(3, store.list().size)

        // Delete all: asks first, then everything goes.
        compose.onNodeWithText("Delete all").performClick()
        compose.onNodeWithText("Delete all canvases?").assertIsDisplayed()
        compose.onNodeWithText("All 3 canvases", substring = true).assertIsDisplayed()
        compose.screenshot("12-delete-all-dialog")
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("No canvases yet")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(store.list().isEmpty())
        assertFalse("pictures and EPUBs gone too", File(app.filesDir, "projects").exists())
        compose.onNodeWithText("Delete all").assertDoesNotExist()
    }
}
